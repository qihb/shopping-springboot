package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单与收货地址接口集成测试
 *
 * <p>覆盖「地址默认唯一 → 购物车勾选下单（扣库存 / 清购物车）→ 库存不足回滚 → 越权防护
 * → 支付/发货/确认收货状态流转 → 取消回滚库存 → 后台权限隔离」全链路，
 * 使用 H2 内存库 + Flyway 自动建表，不依赖本地 MySQL 与 Redis。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        // 测试环境无 Redis：mock 掉 opsForValue()，避免 NPE（与 CartIntegrationTest 一致）
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 购物车列表读走 Redis Hash 缓存：mock 空实现，entries() 默认返回空 map → 走 miss 回源路径
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void orders_should_require_login() throws Exception {
        mockMvc.perform(get("/api/orders").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void address_crud_should_work_and_keep_single_default() throws Exception {
        String token = loginAndGetToken("order_user_addr");
        Long firstId = createAddress(token, false);
        Long secondId = createAddress(token, true);

        // 第二个地址设为默认后，默认标记全局唯一且落在第二个地址上
        JsonNode list = addressList(token);
        assertEquals(2, list.size());
        assertEquals(1, countDefault(list));
        assertEquals(secondId.longValue(), defaultAddressId(list));

        // 切回第一个地址为默认
        mockMvc.perform(put("/api/addresses/" + firstId + "/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode switched = addressList(token);
        assertEquals(1, countDefault(switched));
        assertEquals(firstId.longValue(), defaultAddressId(switched));
    }

    @Test
    void create_order_with_empty_cart_should_return_4002() throws Exception {
        String token = loginAndGetToken("order_user_empty");
        Long addressId = createAddress(token, true);

        JsonNode json = readJson(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("addressId", addressId))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(4002, json.get("code").asInt());
    }

    @Test
    void create_order_should_deduct_stock_and_clear_checked_items() throws Exception {
        String token = loginAndGetToken("order_user_deduct");
        Long addressId = createAddress(token, true);
        Long skuId = createSku(1, 1, 10);
        addCartItem(token, skuId, 2);

        JsonNode json = readJson(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("addressId", addressId, "remark", "尽快发货"))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
        String orderNo = json.get("data").asText();
        assertFalse(orderNo.isEmpty());

        // 初始库存 10，买 2 → 8；勾选条目已被清理
        assertEquals(8, productSkuMapper.selectById(skuId).getStock());
        assertEquals(0, cartItems(token).size());

        JsonNode orders = readJson(mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertTrue(orders.get("data").get("total").asLong() >= 1);
    }

    @Test
    void create_order_should_return_4004_and_rollback_stock_when_short() throws Exception {
        String token = loginAndGetToken("order_user_short");
        Long addressId = createAddress(token, true);
        Long skuId = createSku(1, 1, 1);
        // 加购时库存校验允许（stock=1，买 1 件），随后直接把库存扣成 0 制造库存不足
        addCartItem(token, skuId, 1);
        productSkuMapper.deductStock(skuId, 1);
        assertEquals(0, productSkuMapper.selectById(skuId).getStock());

        JsonNode json = readJson(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("addressId", addressId))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(4004, json.get("code").asInt());

        // 库存未被扣成负数，购物车条目仍在
        assertEquals(0, productSkuMapper.selectById(skuId).getStock());
        assertEquals(1, cartItems(token).size());
    }

    @Test
    void create_order_with_others_address_should_return_4001() throws Exception {
        String ownerToken = loginAndGetToken("order_user_a");
        Long ownerAddressId = createAddress(ownerToken, true);

        String otherToken = loginAndGetToken("order_user_b");
        JsonNode json = readJson(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("addressId", ownerAddressId))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(4001, json.get("code").asInt());
    }

    @Test
    void pay_then_ship_then_confirm_should_follow_status_flow() throws Exception {
        String token = loginAndGetToken("order_user_flow");
        Long addressId = createAddress(token, true);
        Long skuId = createSku(1, 1, 10);
        addCartItem(token, skuId, 1);
        String orderNo = createOrder(token, addressId);

        // 支付：待付款 → 待发货
        assertEquals(200, postAction("/api/orders/" + orderNo + "/pay", token).get("code").asInt());

        // 后台发货：待发货 → 待收货
        String adminToken = loginAsAdmin();
        assertEquals(200, postAction("/api/admin/orders/" + orderNo + "/ship", adminToken).get("code").asInt());

        // 确认收货：待收货 → 已完成（status=4）
        assertEquals(200, postAction("/api/orders/" + orderNo + "/confirm", token).get("code").asInt());

        JsonNode detail = readJson(mockMvc.perform(get("/api/orders/" + orderNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(4, detail.get("data").get("status").asInt());
    }

    @Test
    void cancel_should_restore_stock_and_keep_order() throws Exception {
        String token = loginAndGetToken("order_user_cancel");
        Long addressId = createAddress(token, true);
        Long skuId = createSku(1, 1, 10);
        addCartItem(token, skuId, 2);
        String orderNo = createOrder(token, addressId);
        assertEquals(8, productSkuMapper.selectById(skuId).getStock());

        assertEquals(200, postAction("/api/orders/" + orderNo + "/cancel", token).get("code").asInt());

        // 取消回滚库存，订单仍可查询且状态为已取消（5）
        assertEquals(10, productSkuMapper.selectById(skuId).getStock());
        JsonNode detail = readJson(mockMvc.perform(get("/api/orders/" + orderNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(5, detail.get("data").get("status").asInt());
    }

    @Test
    void detail_of_others_order_should_return_4005() throws Exception {
        String ownerToken = loginAndGetToken("order_user_owner");
        Long addressId = createAddress(ownerToken, true);
        Long skuId = createSku(1, 1, 10);
        addCartItem(ownerToken, skuId, 1);
        String orderNo = createOrder(ownerToken, addressId);

        String otherToken = loginAndGetToken("order_user_other");
        JsonNode json = readJson(mockMvc.perform(get("/api/orders/" + orderNo)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(4005, json.get("code").asInt());
    }

    @Test
    void admin_orders_should_reject_front_user_token() throws Exception {
        String token = loginAndGetToken("order_user_front");

        mockMvc.perform(get("/api/admin/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 注册并登录，返回可用的前台 token
     */
    private String loginAndGetToken(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("data").get("token").asText();
        assertNotNull(token);
        return token;
    }

    /**
     * 管理员登录（初始数据由 AdminDataInitializer 注入 admin / admin123），返回后台 token
     */
    private String loginAsAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    /**
     * 构造一个商品 + SKU，返回 SKU id（价格 10.00）
     */
    private Long createSku(int productStatus, int skuStatus, int stock) {
        Product product = new Product();
        product.setCategoryId(1L);
        product.setName("测试商品");
        product.setStatus(productStatus);
        productMapper.insert(product);

        ProductSku sku = new ProductSku();
        sku.setProductId(product.getId());
        sku.setSkuCode("SKU-" + System.nanoTime());
        sku.setPrice(new BigDecimal("10.00"));
        sku.setStock(stock);
        sku.setStatus(skuStatus);
        productSkuMapper.insert(sku);
        return sku.getId();
    }

    /**
     * 新增一个收货地址，返回地址 id
     */
    private Long createAddress(String token, boolean isDefault) throws Exception {
        Map<String, Object> body = Map.of(
                "receiverName", "张三",
                "receiverPhone", "13800138000",
                "province", "广东省",
                "city", "深圳市",
                "district", "南山区",
                "detailAddress", "科技园 1 号",
                "isDefault", isDefault);
        JsonNode json = readJson(mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
        return json.get("data").asLong();
    }

    /**
     * 加入购物车
     */
    private void addCartItem(String token, Long skuId, int quantity) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("skuId", skuId, "quantity", quantity))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
    }

    /**
     * 下单，断言成功并返回订单号
     */
    private String createOrder(String token, Long addressId) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("addressId", addressId, "remark", "尽快发货"))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
        String orderNo = json.get("data").asText();
        assertNotNull(orderNo);
        return orderNo;
    }

    /**
     * 执行一个无 body 的 POST 动作（支付 / 取消 / 确认 / 发货），返回响应体
     */
    private JsonNode postAction(String url, String token) throws Exception {
        return readJson(mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode addressList(String token) throws Exception {
        return readJson(mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).get("data");
    }

    private int countDefault(JsonNode list) {
        int count = 0;
        for (JsonNode node : list) {
            if (node.get("isDefault").asBoolean()) {
                count++;
            }
        }
        return count;
    }

    private long defaultAddressId(JsonNode list) {
        for (JsonNode node : list) {
            if (node.get("isDefault").asBoolean()) {
                return node.get("id").asLong();
            }
        }
        return -1L;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 读取当前用户购物车条目数组
     */
    private JsonNode cartItems(String token) throws Exception {
        return readJson(mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items");
    }
}
