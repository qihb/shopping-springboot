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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 购物车接口集成测试
 *
 * <p>覆盖「未登录拦截 → 加购 → 列表 → 重复加购累加 → 删除后再次加购」全链路，
 * 使用 H2 内存库 + Flyway 自动建表，不依赖本地 MySQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CartIntegrationTest {

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
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void cart_should_require_login() throws Exception {
        mockMvc.perform(get("/api/cart").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void add_then_list_should_return_item() throws Exception {
        String token = loginAndGetToken("cart_user_1");
        Long skuId = createSku(1, 1, 10);

        addItem(token, skuId, 2);

        JsonNode json = readJson(mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
        JsonNode data = json.get("data");
        assertEquals(1, data.get("items").size());
        JsonNode item = data.get("items").get(0);
        assertEquals("测试商品", item.get("productName").asText());
        assertEquals(2, item.get("quantity").asInt());
        assertEquals(false, item.get("invalid").asBoolean());
        assertEquals(2, data.get("totalQuantity").asInt());
        assertEquals(2, data.get("checkedQuantity").asInt());
        assertEquals(0, new BigDecimal("20.00").compareTo(new BigDecimal(data.get("checkedAmount").asText())));
    }

    @Test
    void add_same_sku_twice_should_accumulate() throws Exception {
        String token = loginAndGetToken("cart_user_2");
        Long skuId = createSku(1, 1, 10);

        addItem(token, skuId, 1);
        addItem(token, skuId, 3);

        JsonNode json = readJson(mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode items = json.get("data").get("items");
        assertEquals(1, items.size());
        assertEquals(4, items.get(0).get("quantity").asInt());
    }

    @Test
    void delete_then_add_same_sku_should_succeed() throws Exception {
        String token = loginAndGetToken("cart_user_3");
        Long skuId = createSku(1, 1, 10);

        addItem(token, skuId, 2);
        Long itemId = readJson(mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andReturn()).get("data").get("items").get(0).get("id").asLong();

        mockMvc.perform(delete("/api/cart/items/" + itemId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 物理删除决策的守门测试：删除后再次加购同一 SKU 必须成功（不会与旧行唯一键冲突）
        addItem(token, skuId, 1);

        JsonNode items = readJson(mockMvc.perform(get("/api/cart")
                        .header("Authorization", "Bearer " + token))
                .andReturn()).get("data").get("items");
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).get("quantity").asInt());
    }

    @Test
    void add_off_shelf_product_should_return_off_shelf() throws Exception {
        String token = loginAndGetToken("cart_user_4");
        Long skuId = createSku(0, 1, 10);

        JsonNode json = readJson(mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("skuId", skuId, "quantity", 1))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(2014, json.get("code").asInt());
    }

    @Test
    void update_quantity_and_checked_then_select_all_should_work() throws Exception {
        String token = loginAndGetToken("cart_user_5");
        addItem(token, createSku(1, 1, 10), 2);
        Long itemId = cartItems(token).get(0).get("id").asLong();

        // 改数量：2 -> 5
        mockMvc.perform(put("/api/cart/items/" + itemId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quantity", 5))))
                .andExpect(status().isOk());

        // 取消单条勾选
        mockMvc.perform(put("/api/cart/items/" + itemId + "/checked")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("checked", false))))
                .andExpect(status().isOk());

        JsonNode item = cartItems(token).get(0);
        assertEquals(5, item.get("quantity").asInt());
        assertEquals(false, item.get("checked").asBoolean());

        // 全选后恢复勾选
        mockMvc.perform(put("/api/cart/checked")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("checked", true))))
                .andExpect(status().isOk());

        assertEquals(true, cartItems(token).get(0).get("checked").asBoolean());
    }

    @Test
    void update_quantity_with_illegal_value_should_be_rejected() throws Exception {
        String token = loginAndGetToken("cart_user_6");
        addItem(token, createSku(1, 1, 10), 2);
        Long itemId = cartItems(token).get(0).get("id").asLong();

        JsonNode json = readJson(mockMvc.perform(put("/api/cart/items/" + itemId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quantity", 0))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(400, json.get("code").asInt());
    }

    @Test
    void delete_checked_should_only_remove_checked_items() throws Exception {
        String token = loginAndGetToken("cart_user_7");
        addItem(token, createSku(1, 1, 10), 1);
        addItem(token, createSku(1, 1, 10), 1);

        // 列表按 id 倒序，index 1 是较早加入的条目；取消其勾选后它应被保留
        Long keptId = cartItems(token).get(1).get("id").asLong();
        mockMvc.perform(put("/api/cart/items/" + keptId + "/checked")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("checked", false))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/cart/checked").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode remaining = cartItems(token);
        assertEquals(1, remaining.size());
        assertEquals(keptId.longValue(), remaining.get(0).get("id").asLong());
    }

    @Test
    void clear_should_remove_all_items() throws Exception {
        String token = loginAndGetToken("cart_user_8");
        addItem(token, createSku(1, 1, 10), 1);
        addItem(token, createSku(1, 1, 10), 2);

        mockMvc.perform(delete("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertEquals(0, cartItems(token).size());
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

    private void addItem(String token, Long skuId, int quantity) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("skuId", skuId, "quantity", quantity))))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(200, json.get("code").asInt());
    }

    /**
     * 构造一个商品 + SKU，返回 SKU id
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
