package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加购未买召回圈人集成测试
 *
 * <p>覆盖「管理员登录 → 手动触发圈人 → 查询人群池与选品结果」全链路，
 * 校验口径：加购超过闲置窗口仍未下单的用户入池、可触达标识正确、选品按弃购率排序。
 *
 * <p>测试环境无 Redis，用 {@link MockBean} 替换 StringRedisTemplate；
 * 分布式锁的 {@code setIfAbsent} 返回 null 时会降级为不加锁执行，正好让链路跑通。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecallIntegrationTest {

    private static final String BUILD_URL = "/api/admin/stats/recall/build";
    private static final String SUMMARY_URL = "/api/admin/stats/recall/summary";
    private static final String PRODUCTS_URL = "/api/admin/stats/recall/products";
    private static final String TARGETS_URL = "/api/admin/stats/recall/targets";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 必须显式打桩：Mockito 对未打桩的 Boolean 方法默认返回 false（而非 null），
        // 会被锁逻辑判定为「已被其他实例占用」而拒绝执行
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    /**
     * 圈人主链路：3 个用户对同一商品加购 30 小时未下单 → 全部入池，选品结果里该商品弃购率 100%
     */
    @Test
    void buildRecall_should_pool_abandoned_cart_items_and_rank_product() throws Exception {
        seedProduct(9001L, 9101L, "召回测试商品");
        seedUser(7001L, "13800007001");
        seedUser(7002L, "13800007002");
        seedUser(7003L, null);
        seedCartItem(7001L, 9101L, 2, 30);
        seedCartItem(7002L, 9101L, 1, 40);
        seedCartItem(7003L, 9101L, 1, 50);

        String token = adminToken();
        String buildBody = mockMvc.perform(post(BUILD_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode build = objectMapper.readTree(buildBody);
        assertEquals(200, build.get("code").asInt());
        assertEquals(3, build.get("data").get("targetCount").asInt());
        assertEquals(3, build.get("data").get("userCount").asInt());
        // 3 个用户里有 2 个带手机号，可触达条目为 2
        assertEquals(2, build.get("data").get("reachableCount").asInt());

        String summaryBody = mockMvc.perform(get(SUMMARY_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode summary = objectMapper.readTree(summaryBody).get("data");
        assertEquals(3, summary.get("totalItems").asInt());
        assertEquals(3, summary.get("totalUsers").asInt());
        assertEquals(2, summary.get("phoneUsers").asInt());
        assertEquals(3, summary.get("pendingItems").asInt());

        String productsBody = mockMvc.perform(get(PRODUCTS_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode products = objectMapper.readTree(productsBody).get("data");
        assertTrue(products.size() >= 1);
        JsonNode first = products.get(0);
        assertEquals(9001L, first.get("productId").asLong());
        assertEquals(1, first.get("rankNo").asInt());
        assertEquals(3, first.get("abandonUserCnt").asInt());
        assertEquals(0, first.get("paidUserCnt").asInt());
        // 3 人加购 0 人成交 → 弃购率 100%
        assertEquals(0, new BigDecimal("1.0000").compareTo(new BigDecimal(first.get("abandonRate").asText())));
    }

    /**
     * 闲置窗口过滤：刚加购（1 小时前）的条目不应进入召回池 —— 立刻发券是骚扰，用户本来可能就要买
     */
    @Test
    void buildRecall_should_skip_fresh_cart_items() throws Exception {
        seedProduct(9002L, 9102L, "刚加购商品");
        seedUser(7101L, "13800007101");
        seedCartItem(7101L, 9102L, 1, 1);

        String token = adminToken();
        String body = mockMvc.perform(post(BUILD_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(0, objectMapper.readTree(body).get("data").get("targetCount").asInt());
    }

    /**
     * 幂等：同一天重复执行圈人，结果一致且不产生重复条目
     */
    @Test
    void buildRecall_should_be_idempotent() throws Exception {
        seedProduct(9003L, 9103L, "幂等测试商品");
        seedUser(7201L, "13800007201");
        seedCartItem(7201L, 9103L, 1, 30);

        String token = adminToken();
        mockMvc.perform(post(BUILD_URL).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post(BUILD_URL).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_recall_target WHERE user_id = 7201", Integer.class);
        assertEquals(1, count);
    }

    /**
     * 可触达筛选：reachableOnly=true 只返回有手机号或 openid 的条目
     */
    @Test
    void listTargets_should_filter_unreachable_when_requested() throws Exception {
        seedProduct(9004L, 9104L, "触达筛选商品");
        seedUser(7301L, "13800007301");
        seedUser(7302L, null);
        seedCartItem(7301L, 9104L, 1, 30);
        seedCartItem(7302L, 9104L, 1, 30);

        String token = adminToken();
        mockMvc.perform(post(BUILD_URL).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        String body = mockMvc.perform(get(TARGETS_URL + "?reachableOnly=true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertEquals(1, data.size());
        assertEquals(1, data.get(0).get("reachable").asInt());
    }

    /**
     * 后台接口必须要求管理员身份：无 token 访问返回 401
     */
    @Test
    void recallApi_should_reject_anonymous_access() throws Exception {
        mockMvc.perform(get(SUMMARY_URL)).andExpect(status().isUnauthorized());
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    private void seedProduct(Long productId, Long skuId, String name) {
        jdbcTemplate.update("INSERT INTO product_category (id, parent_id, name, sort, status) "
                + "VALUES (?, 0, ?, 1, 1)", productId, "测试分类" + productId);
        jdbcTemplate.update("INSERT INTO product (id, category_id, name, subtitle, sales, status, is_deleted, version) "
                + "VALUES (?, ?, ?, ?, 0, 1, 0, 0)", productId, productId, name, "召回集成测试");
        jdbcTemplate.update("INSERT INTO product_sku (id, product_id, sku_code, specs, price, stock, status, is_deleted, version) "
                + "VALUES (?, ?, ?, ?, ?, 100, 1, 0, 0)", skuId, productId, "SKU-RECALL-" + skuId, "规格:默认", new BigDecimal("199.00"));
    }

    private void seedUser(Long userId, String phone) {
        jdbcTemplate.update("INSERT INTO `user` (id, username, password, nickname, phone, status, is_deleted, version) "
                + "VALUES (?, ?, 'x', ?, ?, 1, 0, 0)", userId, "recall_u" + userId, "召回用户" + userId, phone);
    }

    /**
     * 造一条「加购已闲置 idleHours 小时」的购物车条目：
     * create_time 只在首次加购时写入，是圈人判断闲置时长的唯一依据
     */
    private void seedCartItem(Long userId, Long skuId, int quantity, int idleHours) {
        jdbcTemplate.update("INSERT INTO cart_item (user_id, sku_id, quantity, checked, create_time, update_time, is_deleted, version) "
                        + "VALUES (?, ?, ?, 1, ?, ?, 0, 0)",
                userId, skuId, quantity,
                Timestamp.valueOf(LocalDateTime.now().minusHours(idleHours)),
                Timestamp.valueOf(LocalDateTime.now().minusHours(idleHours)));
    }
}
