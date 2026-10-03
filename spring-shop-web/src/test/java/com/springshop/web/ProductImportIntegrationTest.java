package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品批量导入集成测试
 *
 * <p>覆盖「管理员登录 → 建分类 → 上传 Excel → 落库 SPU/SKU → 后台列表可见」全链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductImportIntegrationTest {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final List<String> IMPORT_HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    private static final String CATEGORY_NAME = "手机";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void importApi_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(multipart("/api/admin/products/import")
                        .file(new MockMultipartFile("file", "商品.xlsx", XLSX_CONTENT_TYPE, new byte[]{1})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void importProducts_shouldCreateOneProductWithMultipleSkus() throws Exception {
        String token = loginAsAdmin();
        createCategory(token);

        JsonNode data = importProducts(token, List.of(
                importRow("导入商品A", "SKU-IMP-001", "199.00", "10"),
                importRow("导入商品A", "SKU-IMP-002", "299.00", "20")));

        assertEquals(2, data.get("totalRows").asInt());
        assertEquals(1, data.get("productCount").asInt());
        assertEquals(2, data.get("skuCount").asInt());
        assertEquals(0, data.get("failRowCount").asInt());
    }

    @Test
    void importedProduct_shouldBeVisibleInAdminList() throws Exception {
        String token = loginAsAdmin();
        createCategory(token);
        importProducts(token, List.of(importRow("导入商品B", "SKU-IMP-100", "88.00", "5")));

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/products")
                        .param("keyword", "导入商品B")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("records");

        assertEquals(1, records.size());
        assertTrue(records.get(0).toString().contains("导入商品B"));
    }

    @Test
    void importProducts_withUnknownCategory_shouldFailAllRowsOfThatProduct() throws Exception {
        String token = loginAsAdmin();
        createCategory(token);

        JsonNode data = importProducts(token, List.of(
                List.of("导入商品C", "副标题", "", "不存在的分类", "SKU-IMP-200", "", "99.00", "", "1", "1"),
                List.of("导入商品C", "副标题", "", "不存在的分类", "SKU-IMP-201", "", "99.00", "", "1", "1")));

        assertEquals(0, data.get("productCount").asInt());
        assertEquals(2, data.get("failRowCount").asInt());
        assertTrue(data.get("errors").get(0).get("message").asText().contains("不存在"));
    }

    @Test
    void importProducts_withDuplicateSkuCodeInDb_shouldFailOnlyThatRow() throws Exception {
        String token = loginAsAdmin();
        createCategory(token);
        importProducts(token, List.of(importRow("导入商品D", "SKU-IMP-300", "66.00", "3")));

        // 第二次导入复用同一 SKU 编码：该行失败，其余行照常导入
        JsonNode data = importProducts(token, List.of(
                importRow("导入商品E", "SKU-IMP-300", "66.00", "3"),
                importRow("导入商品E", "SKU-IMP-301", "77.00", "3")));

        assertEquals(1, data.get("productCount").asInt());
        assertEquals(1, data.get("skuCount").asInt());
        assertEquals(1, data.get("failRowCount").asInt());
        assertTrue(data.get("errors").get(0).get("message").asText().contains("SKU-IMP-300"));
    }

    @Test
    void importProducts_withNonExcelFile_shouldReturnFileInvalid() throws Exception {
        String token = loginAsAdmin();

        JsonNode json = readJson(mockMvc.perform(multipart("/api/admin/products/import")
                        .file(new MockMultipartFile("file", "商品.csv", MediaType.TEXT_PLAIN_VALUE,
                                "not excel".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(2020, json.get("code").asInt());
    }

    @Test
    void downloadImportTemplate_shouldReturnParseableWorkbookWithSample() throws Exception {
        String token = loginAsAdmin();

        byte[] content = mockMvc.perform(get("/api/admin/products/import/template")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(content.length > 0);
        List<ExcelRow> rows = ExcelSupport.read(new ByteArrayInputStream(content), 10);
        // 模板用两行同名商品演示「一个 SPU 两个 SKU」
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).cell(0), rows.get(1).cell(0));
    }

    private List<String> importRow(String productName, String skuCode, String price, String stock) {
        return List.of(productName, "副标题", "https://example.com/a.jpg", CATEGORY_NAME,
                skuCode, "颜色:黑", price, "", stock, "1");
    }

    private JsonNode importProducts(String token, List<List<String>> rows) throws Exception {
        byte[] content = ExcelSupport.write("商品导入模板", IMPORT_HEADERS, rows);
        return readJson(mockMvc.perform(multipart("/api/admin/products/import")
                        .file(new MockMultipartFile("file", "商品.xlsx", XLSX_CONTENT_TYPE, content))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private void createCategory(String token) throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", 0, "name", CATEGORY_NAME))))
                .andExpect(status().isOk());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
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
