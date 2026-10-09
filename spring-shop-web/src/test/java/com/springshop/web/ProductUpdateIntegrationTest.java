package com.springshop.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.product.product.entity.Inventory;
import com.springshop.product.product.mapper.InventoryMapper;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品「修改 / 删除 / 唯一性」集成测试（真连 H2 跑完整 HTTP 链路）
 *
 * <p><b>本类存在的主要理由</b>：此前<b>没有任何用例打过
 * {@code PUT /api/admin/products/{id}}</b>，而修改商品的服务实现是纯 Mockito 单测
 * （mapper 被 mock，不碰库）。于是下面三个必然故障在测试里<b>结构性不可见</b>，
 * 一路漏到了线上：
 * <ol>
 *   <li><b>改商品必 500</b>：旧实现「先逻辑删除全部 SKU 再重新插入」，
 *       而逻辑删除的行仍占着 {@code uk_sku_code}（该唯一索引不区分 {@code is_deleted}），
 *       插入同一个编码直接撞唯一键；</li>
 *   <li><b>库存被静默清零</b>：{@code ProductSkuItem.stock} 没有 {@code @NotNull}，
 *       写入用 {@code requireNonNullElse(stock, 0)}，前端不传库存就变 0；</li>
 *   <li><b>SKU id 全变</b>：删旧插新让新 SKU 拿到新自增 id，而
 *       {@code cart_item.sku_id} / {@code order_item.sku_id} 仍指向旧 SKU。</li>
 * </ol>
 *
 * <p>断言一律走真实 HTTP + 真实库，不依赖任何「总数」：分类等前置数据在本类内自建，
 * 且因为带 {@code @Transactional}（回滚），不会污染共享的 {@code jdbc:h2:mem:spring_shop_test}。
 * 名称仍带 {@link #RUN_TAG} 后缀，避免与其它测试类的数据撞唯一键。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductUpdateIntegrationTest {

    /** 本次 JVM 运行的唯一后缀，避免与其它测试类、以及重复运行时的数据撞唯一键 */
    private static final String RUN_TAG = Long.toString(System.nanoTime() % 1_000_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private String token;

    private Long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        token = loginAsAdmin();
        categoryId = createCategory("改商品测试分类-" + RUN_TAG);
    }

    // ------------------------------------------------------------------
    // 修改商品：Bug A / B / C 的回归防线
    // ------------------------------------------------------------------

    /**
     * Bug A + C：沿用同一个 {@code sku_code} 修改商品必须成功，且<b>保留原 {@code sku_id}</b>。
     */
    @Test
    void update_withSameSkuCode_shouldSucceed_andKeepSkuId() throws Exception {
        String name = "改商品A-" + RUN_TAG;
        String skuCode = "SKU-UPD-A-" + RUN_TAG;
        long productId = createProduct(name, List.of(sku(skuCode, "颜色:黑", "199.00", 10)));
        long skuIdBefore = firstSkuId(productId);

        JsonNode updated = putJson("/api/admin/products/" + productId,
                productBody(name, List.of(sku(skuCode, "颜色:黑", "299.00", 10))));

        assertEquals(200, updated.get("code").asInt(), () -> "改商品必须成功，实际: " + updated);

        JsonNode detail = productDetail(productId);
        assertEquals(1, detail.get("skus").size());
        assertEquals(skuIdBefore, detail.get("skus").get(0).get("id").asLong(),
                "必须保留原 sku_id，否则 cart_item / order_item 会变成脏引用");
        assertEquals(0, new BigDecimal("299.00")
                        .compareTo(detail.get("skus").get(0).get("price").decimalValue()),
                "价格应已更新，实际: " + detail);
    }

    /**
     * Bug B：请求里不带 {@code stock} 时必须<b>保持原值</b>，不能当成 0。
     *
     * <p>断言落在 {@code inventory} 表上（库存的唯一真相源）；{@code product_sku} 上已无库存列。
     */
    @Test
    void update_withoutStock_shouldKeepInventoryStock() throws Exception {
        String name = "改商品B-" + RUN_TAG;
        String skuCode = "SKU-UPD-B-" + RUN_TAG;
        long productId = createProduct(name, List.of(sku(skuCode, "颜色:黑", "199.00", 10)));
        long skuId = firstSkuId(productId);
        assertEquals(10, inventoryStock(skuId));

        putJson("/api/admin/products/" + productId,
                productBody(name, List.of(sku(skuCode, "颜色:黑", "188.00", null))));

        assertEquals(10, inventoryStock(skuId), "不传库存必须保持原值，不能悄悄清零");
    }

    /**
     * 传了 {@code stock} 就应生效：库存同步要经 {@code InventoryService} 落库（而不是只改镜像）。
     */
    @Test
    void update_withChangedStock_shouldApplyToInventory() throws Exception {
        String name = "改商品C-" + RUN_TAG;
        String skuCode = "SKU-UPD-C-" + RUN_TAG;
        long productId = createProduct(name, List.of(sku(skuCode, "颜色:黑", "199.00", 10)));
        long skuId = firstSkuId(productId);

        putJson("/api/admin/products/" + productId,
                productBody(name, List.of(sku(skuCode, "颜色:黑", "199.00", 25))));

        assertEquals(25, inventoryStock(skuId), "传了库存就应同步到 inventory 表");
    }

    /**
     * 请求里没有的 SKU 要逻辑删除（前端契约是「提交即全量」）。
     */
    @Test
    void update_withoutOneSku_shouldRemoveIt() throws Exception {
        String name = "改商品D-" + RUN_TAG;
        String kept = "SKU-UPD-D1-" + RUN_TAG;
        long productId = createProduct(name, List.of(
                sku(kept, "颜色:黑", "199.00", 10),
                sku("SKU-UPD-D2-" + RUN_TAG, "颜色:白", "199.00", 10)));
        assertEquals(2, productDetail(productId).get("skus").size());

        putJson("/api/admin/products/" + productId,
                productBody(name, List.of(sku(kept, "颜色:黑", "199.00", 10))));

        JsonNode skus = productDetail(productId).get("skus");
        assertEquals(1, skus.size(), () -> "请求里没有的 SKU 应被移除，实际: " + skus);
        assertEquals(kept, skus.get(0).get("skuCode").asText());
    }

    // ------------------------------------------------------------------
    // 唯一性：(商品名称, 规格) 未删除范围内唯一
    // ------------------------------------------------------------------

    @Test
    void create_withSameNameAndSpecs_shouldBeRejected() throws Exception {
        String name = "唯一性商品A-" + RUN_TAG;
        createProduct(name, List.of(sku("SKU-UNIQ-A1-" + RUN_TAG, "颜色:黑", "199.00", 10)));

        JsonNode second = createProductRaw(name, List.of(sku("SKU-UNIQ-A2-" + RUN_TAG, "颜色:黑", "199.00", 10)));

        assertEquals(2015, second.get("code").asInt(), () -> "同名同规格必须拒绝，实际: " + second);
    }

    /**
     * 同名 + 不同规格是<b>合法</b>的（同一 SPU 下的两个 SKU 的写法）。
     */
    @Test
    void create_withSameNameButDifferentSpecs_shouldBeAllowed() throws Exception {
        String name = "唯一性商品B-" + RUN_TAG;

        JsonNode first = createProductRaw(name, List.of(sku("SKU-UNIQ-B1-" + RUN_TAG, "颜色:黑", "199.00", 10)));
        JsonNode second = createProductRaw(name, List.of(sku("SKU-UNIQ-B2-" + RUN_TAG, "颜色:白", "199.00", 10)));

        assertEquals(200, first.get("code").asInt());
        assertEquals(200, second.get("code").asInt(), () -> "同名不同规格必须放行，实际: " + second);
    }

    /**
     * 规格比较前必须规范化，否则全角冒号 / 段序 / 空格都能把唯一性绕过去。
     */
    @Test
    void create_withFullWidthColonSpecs_shouldBeRejectedAsDuplicate() throws Exception {
        String name = "唯一性商品C-" + RUN_TAG;
        createProduct(name, List.of(sku("SKU-UNIQ-C1-" + RUN_TAG, "颜色:黑", "199.00", 10)));

        JsonNode second = createProductRaw(name, List.of(sku("SKU-UNIQ-C2-" + RUN_TAG, "颜色：黑", "199.00", 10)));

        assertEquals(2015, second.get("code").asInt(),
                () -> "全角冒号必须被认成同一个规格，实际: " + second);
    }

    /**
     * 一次提交内部的规格重复也要拦（{@code sku_code} 查重拦不住这个）。
     */
    @Test
    void create_withDuplicateSpecsInsideRequest_shouldBeRejected() throws Exception {
        String name = "唯一性商品D-" + RUN_TAG;

        JsonNode resp = createProductRaw(name, List.of(
                sku("SKU-UNIQ-D1-" + RUN_TAG, "颜色:黑", "199.00", 10),
                sku("SKU-UNIQ-D2-" + RUN_TAG, "颜色：黑", "199.00", 10)));

        assertEquals(2015, resp.get("code").asInt(), () -> "同一次提交内规格重复必须拒绝，实际: " + resp);
    }

    /**
     * 改名撞上别的商品已占用的规格 → 拒绝。
     */
    @Test
    void update_withSpecsTakenByAnotherProduct_shouldBeRejected() throws Exception {
        String takenName = "唯一性商品E-" + RUN_TAG;
        createProduct(takenName, List.of(sku("SKU-UNIQ-E1-" + RUN_TAG, "颜色:黑", "199.00", 10)));

        String otherName = "唯一性商品F-" + RUN_TAG;
        String otherSku = "SKU-UNIQ-F1-" + RUN_TAG;
        long otherId = createProduct(otherName, List.of(sku(otherSku, "颜色:白", "199.00", 10)));

        JsonNode resp = putJson("/api/admin/products/" + otherId,
                productBody(takenName, List.of(sku(otherSku, "颜色:黑", "199.00", 10))));

        assertEquals(2015, resp.get("code").asInt(), () -> "撞上别的商品的规格必须拒绝，实际: " + resp);
    }

    /**
     * 修改商品时必须排除「自己」：否则它自己的规格会把它自己挡住。
     */
    @Test
    void update_shouldNotTreatOwnSpecsAsConflict() throws Exception {
        String name = "唯一性商品G-" + RUN_TAG;
        String skuCode = "SKU-UNIQ-G1-" + RUN_TAG;
        long productId = createProduct(name, List.of(sku(skuCode, "颜色:黑", "199.00", 10)));

        JsonNode resp = putJson("/api/admin/products/" + productId,
                productBody(name, List.of(sku(skuCode, "颜色:黑", "188.00", 10))));

        assertEquals(200, resp.get("code").asInt(), () -> "自己的规格不能挡住自己，实际: " + resp);
    }

    // ------------------------------------------------------------------
    // 删除商品：仅下架可删
    // ------------------------------------------------------------------

    @Test
    void delete_shouldRequireOffShelf_thenRemoveFromList() throws Exception {
        String name = "删除商品A-" + RUN_TAG;
        long productId = createProduct(name, List.of(sku("SKU-DEL-A-" + RUN_TAG, "颜色:黑", "199.00", 10)));

        // 上架状态删除被拒（2016）
        JsonNode rejected = deleteJson("/api/admin/products/" + productId);
        assertEquals(2016, rejected.get("code").asInt(), () -> "上架商品不可删，实际: " + rejected);

        // 下架后可以删
        mockMvc.perform(put("/api/admin/products/" + productId + "/status")
                        .param("status", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode deleted = deleteJson("/api/admin/products/" + productId);
        assertEquals(200, deleted.get("code").asInt(), () -> "下架商品应可删，实际: " + deleted);

        assertEquals(0, listByKeyword(name).size(),
                () -> "删除后不应再出现在后台列表里");
    }

    @Test
    void delete_shouldRejectUnknownProduct() throws Exception {
        JsonNode resp = deleteJson("/api/admin/products/99999999");

        assertEquals(2010, resp.get("code").asInt(), () -> "商品不存在应返回 2010，实际: " + resp);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private Map<String, Object> sku(String skuCode, String specs, String price, Integer stock) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("skuCode", skuCode);
        map.put("specs", specs);
        map.put("price", price);
        // 刻意「不传」stock 来模拟前端省略该字段（ProductSkuItem.stock 没有 @NotNull）
        if (stock != null) {
            map.put("stock", stock);
        }
        map.put("status", 1);
        return map;
    }

    private Map<String, Object> productBody(String name, List<Map<String, Object>> skus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("status", 1);
        body.put("skus", skus);
        return body;
    }

    /** 创建商品并返回原始响应（负向用例要断言业务码） */
    private JsonNode createProductRaw(String name, List<Map<String, Object>> skus) throws Exception {
        return postJson("/api/admin/products", productBody(name, skus));
    }

    /** 创建商品并返回其 id（业务码非 200 时直接失败，避免后续断言出现误导性报错） */
    private long createProduct(String name, List<Map<String, Object>> skus) throws Exception {
        JsonNode json = createProductRaw(name, skus);
        assertEquals(200, json.get("code").asInt(), () -> "创建商品失败，实际: " + json);
        return json.get("data").asLong();
    }

    private JsonNode productDetail(long productId) throws Exception {
        return getJson("/api/admin/products/" + productId).get("data");
    }

    private long firstSkuId(long productId) throws Exception {
        return productDetail(productId).get("skus").get(0).get("id").asLong();
    }

    /** 直接查库存表：库存的唯一真相源（product_sku 上已无库存列） */
    private int inventoryStock(long skuId) {
        Inventory inventory = inventoryMapper.selectOne(
                Wrappers.<Inventory>lambdaQuery().eq(Inventory::getSkuId, skuId));
        return inventory.getStock();
    }

    private JsonNode listByKeyword(String keyword) throws Exception {
        String content = mockMvc.perform(get("/api/admin/products")
                        .param("keyword", keyword)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content).get("data").get("records");
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        return readJson(mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode putJson(String url, Object body) throws Exception {
        return readJson(mockMvc.perform(put(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return readJson(mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode deleteJson(String url) throws Exception {
        return readJson(mockMvc.perform(delete(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private long createCategory(String name) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("parentId", 0, "name", name))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, json.get("code").asInt(), () -> "创建分类失败: " + json);

        // 创建接口只返回 Result<Void>，所以按名称回查拿到 id
        JsonNode records = readJson(mockMvc.perform(get("/api/admin/categories/tree")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        Long id = findCategoryId(records, name);
        assertEquals(true, id != null, () -> "回查分类 id 失败，实际: " + records);
        return id;
    }

    private Long findCategoryId(JsonNode nodes, String name) {
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (name.equals(node.path("name").asText())) {
                return node.path("id").asLong();
            }
            Long found = findCategoryId(node.path("children"), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String loginAsAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }
}
