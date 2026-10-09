package com.springshop.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.pay.entity.PayRecord;
import com.springshop.pay.mapper.PayRecordMapper;
import com.springshop.product.product.entity.Inventory;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.InventoryMapper;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付接口集成测试
 *
 * <p>覆盖「注册登录 → 下单 → 模拟支付（订单推进 + 支付流水落库）→ 重复支付幂等
 * → 越权/订单不存在/已取消拒绝 → 未登录 401」全链路，
 * 使用 H2 内存库 + Flyway 自动建表，不依赖本地 MySQL 与 Redis。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private PayRecordMapper payRecordMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /** 最近一次 {@link #createSku()} 建出的 SKU id，供库存断言使用 */
    private Long lastSkuId;

    @BeforeEach
    void setUpRedisMocks() {
        // 测试环境无 Redis：mock 掉 opsForValue()，避免 NPE（与 OrderIntegrationTest 一致）
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 购物车列表读走 Redis Hash 缓存：mock 空实现，entries() 默认返回空 map → 走 miss 回源路径
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void mockPay_should_require_login() throws Exception {
        mockMvc.perform(post("/api/pay/NO20260101/mockPay").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mockPay_should_mark_order_paid_and_insert_record() throws Exception {
        String token = loginAndGetToken("pay_user_flow");
        String orderNo = createPendingOrder(token);

        // 模拟支付：订单待付款 → 待发货，支付流水落库
        JsonNode json = readJson(mockMvc.perform(post("/api/pay/" + orderNo + "/mockPay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
        assertEquals(orderNo, json.get("data").get("orderNo").asText());
        assertEquals(0, json.get("data").get("amount").decimalValue().compareTo(new BigDecimal("10.00")));
        assertEquals(1, json.get("data").get("status").asInt());
        assertNotNull(json.get("data").get("payTime").asText());

        // 订单状态已推进为待发货（2）
        assertEquals(2, orderDetailStatus(token, orderNo));

        // 支付流水：仅一条，金额取订单快照，状态支付成功
        List<PayRecord> records = payRecords(orderNo);
        assertEquals(1, records.size());
        assertEquals(0, records.get(0).getAmount().compareTo(new BigDecimal("10.00")));
        assertEquals(1, records.get(0).getStatus());
        assertNotNull(records.get(0).getPayTime());

        // 支付即出库：下单锁定的 1 件转为已售 —— 在库 10 → 9、锁定 1 → 0
        Inventory inventory = inventoryOf(lastSkuId);
        assertEquals(9, inventory.getStock());
        assertEquals(0, inventory.getLockedStock());
    }

    @Test
    void mockPay_should_be_idempotent_when_paid_again() throws Exception {
        String token = loginAndGetToken("pay_user_idempotent");
        String orderNo = createPendingOrder(token);
        assertEquals(200, mockPay(token, orderNo).get("code").asInt());

        // 重复支付：幂等返回成功，不再产生新流水，订单状态不变
        JsonNode second = mockPay(token, orderNo);
        assertEquals(200, second.get("code").asInt());
        assertEquals(orderNo, second.get("data").get("orderNo").asText());

        assertEquals(1, payRecords(orderNo).size());
        assertEquals(2, orderDetailStatus(token, orderNo));
        // 重复支付不能再出库一次：在库量仍应是 9（条件更新落败 → 不触发 outbound）
        assertEquals(9, inventoryOf(lastSkuId).getStock());
        assertEquals(0, inventoryOf(lastSkuId).getLockedStock());
    }

    @Test
    void mockPay_other_users_order_should_return_6002() throws Exception {
        String ownerToken = loginAndGetToken("pay_user_owner");
        String orderNo = createPendingOrder(ownerToken);

        // 其他用户支付他人订单：6002
        String otherToken = loginAndGetToken("pay_user_other");
        JsonNode json = mockPay(otherToken, orderNo);
        assertEquals(6002, json.get("code").asInt());

        // 订单未被支付、无流水
        assertEquals(1, orderDetailStatus(ownerToken, orderNo));
        assertEquals(0, payRecords(orderNo).size());
    }

    @Test
    void mockPay_missing_order_should_return_6001() throws Exception {
        String token = loginAndGetToken("pay_user_missing");

        JsonNode json = mockPay(token, "NO9999999999999");
        assertEquals(6001, json.get("code").asInt());
        assertEquals(0, payRecords("NO9999999999999").size());
    }

    @Test
    void mockPay_cancelled_order_should_return_6003() throws Exception {
        String token = loginAndGetToken("pay_user_cancel");
        String orderNo = createPendingOrder(token);

        // 先取消订单（待付款 → 已取消），再支付应被拒绝
        assertEquals(200, readJson(mockMvc.perform(post("/api/orders/" + orderNo + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).get("code").asInt());

        JsonNode json = mockPay(token, orderNo);
        assertEquals(6003, json.get("code").asInt());
        assertEquals(5, orderDetailStatus(token, orderNo));
        assertEquals(0, payRecords(orderNo).size());
    }

    // ---------------- 链路辅助（与 OrderIntegrationTest 同一套写法） ----------------

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
     * 构造一个商品 + SKU（价格 10.00），并初始化库存行
     *
     * <p>V9 起库存以 {@code inventory} 表为准，测试手工插 SKU 不会走商品创建路径，
     * 必须自己补一行库存，否则可售量为 0，加购/下单会直接判「库存不足」。
     */
    private Long createSku() {
        Product product = new Product();
        product.setCategoryId(1L);
        product.setName("支付测试商品");
        product.setStatus(1);
        productMapper.insert(product);

        ProductSku sku = new ProductSku();
        sku.setProductId(product.getId());
        sku.setSkuCode("SKU-PAY-" + System.nanoTime());
        sku.setPrice(new BigDecimal("10.00"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);

        Inventory inventory = new Inventory();
        inventory.setSkuId(sku.getId());
        inventory.setStock(10);
        inventory.setLockedStock(0);
        inventoryMapper.insert(inventory);

        lastSkuId = sku.getId();
        return sku.getId();
    }

    /**
     * 读某个 SKU 的库存行（在库量 / 锁定量）
     */
    private Inventory inventoryOf(Long skuId) {
        return inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
    }

    /**
     * 新增收货地址，返回地址 id
     */
    private Long createAddress(String token) throws Exception {
        Map<String, Object> body = Map.of(
                "receiverName", "李四",
                "receiverPhone", "13900139000",
                "province", "广东省",
                "city", "深圳市",
                "district", "福田区",
                "detailAddress", "支付大厦 2 号",
                "isDefault", true);
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
     * 完整构造一笔待付款订单，返回订单号
     */
    private String createPendingOrder(String token) throws Exception {
        Long addressId = createAddress(token);
        Long skuId = createSku();
        JsonNode cartJson = readJson(mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("skuId", skuId, "quantity", 1))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, cartJson.get("code").asInt());

        JsonNode json = readJson(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("addressId", addressId))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
        String orderNo = json.get("data").asText();
        assertNotNull(orderNo);
        return orderNo;
    }

    /**
     * 以指定 token 模拟支付，返回响应体
     */
    private JsonNode mockPay(String token, String orderNo) throws Exception {
        return readJson(mockMvc.perform(post("/api/pay/" + orderNo + "/mockPay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }

    /**
     * 查询订单当前状态
     */
    private int orderDetailStatus(String token, String orderNo) throws Exception {
        JsonNode json = readJson(mockMvc.perform(get("/api/orders/" + orderNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        return json.get("data").get("status").asInt();
    }

    /**
     * 按订单号查支付流水
     */
    private List<PayRecord> payRecords(String orderNo) {
        return payRecordMapper.selectList(new LambdaQueryWrapper<PayRecord>()
                .eq(PayRecord::getOrderNo, orderNo));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
