package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ExcelReadOptions;
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
 * 商品批量导入集成测试（异步受理 → 轮询任务 → 落库结果）
 *
 * <p>覆盖「管理员登录 → 建分类 → 上传 Excel → 受理任务 → 后台线程落库 → 后台列表可见」全链路。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：导入执行体跑在独立的 excel-task 线程池里，
 * 用的是另一条数据库连接。测试方法的事务对那个线程不可见——分类等前置数据会「查不到」，
 * 任务表的状态回写也会落在另一个未提交的快照上。加了只会给出「已经回滚」的错觉。
 *
 * <p>因此本类的数据一律带 {@link #RUN_TAG} 唯一后缀：{@code jdbc:h2:mem:spring_shop_test}
 * 在同一个 surefire JVM 内是共享的，带后缀可避免与其它测试类、以及重复运行时的数据撞唯一键。
 * 断言也一律按名称/编码过滤，不依赖任何「总数」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductImportIntegrationTest {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final List<String> IMPORT_HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    /** 本次 JVM 运行的唯一后缀，避免共享 H2 里的历史数据撞唯一键 */
    private static final String RUN_TAG = Long.toString(System.nanoTime() % 1_000_000);

    private static final String CATEGORY_NAME = "手机-" + RUN_TAG;

    /**
     * 分类是否已创建（整个 JVM 只建一次）
     *
     * <p>「分类」表对名称没有唯一约束，{@code POST /api/admin/categories} 也不查重，
     * 所以每个用例都建一次会往库里堆出多条同名分类；而导入按名称匹配分类时，
     * 遇到同名多条会**宁可整组失败也不猜**（见 {@code ProductImportServiceImpl#resolveCategoryId}）。
     * 结果就是：先跑的用例正常，后面的用例莫名其妙「分类存在多个同名分类」全军覆没。
     */
    private static boolean categoryCreated;

    /** 等待异步任务结束的上限：正常几百毫秒内就结束，给足余量避免 CI 上偶发超时 */
    private static final long TASK_TIMEOUT_MILLIS = 30_000;

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
        ensureCategory(token);

        JsonNode task = runImport(token, List.of(
                importRow("导入商品A-" + RUN_TAG, "SKU-IMP-A1-" + RUN_TAG, "199.00", "10"),
                importRow("导入商品A-" + RUN_TAG, "SKU-IMP-A2-" + RUN_TAG, "299.00", "20")));

        assertEquals(2, task.get("processedRows").asInt(), "详情: " + task);
        assertEquals(2, task.get("successRows").asInt(), "同名两行应聚合为一个 SPU + 两个 SKU，详情: " + task);
        assertEquals(0, task.get("failRows").asInt(), "失败明细: " + task);
        assertEquals("商品导入", task.get("bizName").asText());
    }

    @Test
    void importedProduct_shouldBeVisibleInAdminList() throws Exception {
        String token = loginAsAdmin();
        ensureCategory(token);
        String productName = "导入商品B-" + RUN_TAG;
        runImport(token, List.of(importRow(productName, "SKU-IMP-B1-" + RUN_TAG, "88.00", "5")));

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/products")
                        .param("keyword", productName)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("records");

        assertEquals(1, records.size(), () -> "导入后应在后台列表可见，实际返回: " + records);
        assertTrue(records.get(0).toString().contains(productName));
    }

    @Test
    void importProducts_withUnknownCategory_shouldFailAllRowsOfThatProduct() throws Exception {
        String token = loginAsAdmin();
        ensureCategory(token);

        JsonNode task = runImport(token, List.of(
                List.of("导入商品C-" + RUN_TAG, "副标题", "", "不存在的分类-" + RUN_TAG,
                        "SKU-IMP-C1-" + RUN_TAG, "", "99.00", "", "1", "1"),
                List.of("导入商品C-" + RUN_TAG, "副标题", "", "不存在的分类-" + RUN_TAG,
                        "SKU-IMP-C2-" + RUN_TAG, "", "99.00", "", "1", "1")));

        assertEquals(0, task.get("successRows").asInt());
        assertEquals(2, task.get("failRows").asInt());
        // 导入任务本身是成功的（文件解析没问题），失败体现在行级明细上，可下载查看
        assertEquals(2, task.get("status").asInt());
        assertTrue(task.get("downloadable").asBoolean());
    }

    @Test
    void importProducts_withDuplicateSkuCodeInDb_shouldFailOnlyThatRow() throws Exception {
        String token = loginAsAdmin();
        ensureCategory(token);
        String sharedSkuCode = "SKU-IMP-DUP-" + RUN_TAG;
        runImport(token, List.of(importRow("导入商品D-" + RUN_TAG, sharedSkuCode, "66.00", "3")));

        // 第二次导入复用同一 SKU 编码：该行失败，其余行照常导入
        JsonNode task = runImport(token, List.of(
                importRow("导入商品E-" + RUN_TAG, sharedSkuCode, "66.00", "3"),
                importRow("导入商品E-" + RUN_TAG, "SKU-IMP-E2-" + RUN_TAG, "77.00", "3")));

        assertEquals(1, task.get("successRows").asInt());
        assertEquals(1, task.get("failRows").asInt());
    }

    /**
     * 「同名 + 换一批 SKU 编码」重传：{@code product.name} 上没有唯一索引，原本会静默建出
     * 第二个同名 SPU（运营在列表里看到两个一模一样的商品，事后无法合并）。
     * 现在必须整组拒绝，并且库里仍然只有一个同名商品。
     */
    @Test
    void reimportSameProductNameWithNewSkuCode_shouldBeRejectedInsteadOfCreatingSecondSpu() throws Exception {
        String token = loginAsAdmin();
        ensureCategory(token);
        String productName = "重传商品-" + RUN_TAG;
        runImport(token, List.of(importRow(productName, "SKU-REIMP-1-" + RUN_TAG, "50.00", "3")));

        // SKU 编码是全新的，编码查重拦不住它——只有商品名查重能拦住
        JsonNode task = runImport(token, List.of(
                importRow(productName, "SKU-REIMP-2-" + RUN_TAG, "60.00", "3")));

        assertEquals(0, task.get("successRows").asInt(), "同名商品必须整组拒绝，详情: " + task);
        assertEquals(1, task.get("failRows").asInt(), "详情: " + task);

        // 提示要能让运营知道下一步做什么，而不是只给一句「导入失败」
        List<ExcelRow> errors = readErrorDetail(token, task.get("taskNo").asText());
        assertEquals(1, errors.size());
        String reason = errors.get(0).cell(1);
        assertTrue(reason.contains(productName), "实际: " + reason);
        assertTrue(reason.contains("已存在"), "实际: " + reason);

        // 最关键的断言：库里只有一个同名商品，而不是「报了错却又建出来一个」
        JsonNode records = readJson(mockMvc.perform(get("/api/admin/products")
                        .param("keyword", productName)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("records");
        assertEquals(1, records.size(), () -> "同名商品不应被建出第二个，实际返回: " + records);
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

        // 文件格式校验在「受理阶段」就完成，不占用异步线程，也不产生垃圾任务
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
        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(content),
                ExcelReadOptions.defaults());
        // 模板用两行同名商品演示「一个 SPU 两个 SKU」
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).cell(0), rows.get(1).cell(0));
    }

    // ---------- 测试辅助 ----------

    private List<String> importRow(String productName, String skuCode, String price, String stock) {
        return List.of(productName, "副标题", "https://example.com/a.jpg", CATEGORY_NAME,
                skuCode, "颜色:黑", price, "", stock, "1");
    }

    /**
     * 上传 Excel → 拿到任务号 → 轮询到终态，返回最终的任务详情
     */
    private JsonNode runImport(String token, List<List<String>> rows) throws Exception {
        byte[] content = ExcelSupport.writeDynamic("商品导入模板", IMPORT_HEADERS, rows);
        JsonNode accepted = readJson(mockMvc.perform(multipart("/api/admin/products/import")
                        .file(new MockMultipartFile("file", "商品.xlsx", XLSX_CONTENT_TYPE, content))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");

        assertTrue(accepted.get("taskNo").asText().startsWith("I"), "导入任务号应以 I 开头");
        return awaitFinished(token, accepted.get("taskNo").asText());
    }

    /**
     * 下载失败明细并解析为数据行
     *
     * <p>明细是「按需生成、不落盘」的视图，所以这里走的是一条真实的下载链路，
     * 而不是直接查 {@code excel_task_error} 表。
     */
    private List<ExcelRow> readErrorDetail(String token, String taskNo) throws Exception {
        byte[] detail = mockMvc.perform(get("/api/admin/excel-tasks/" + taskNo + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return ExcelSupport.readAll(new ByteArrayInputStream(detail), ExcelReadOptions.defaults());
    }

    /**
     * 轮询任务详情直到出现终态
     *
     * <p>用轮询而不是 sleep 固定时长：后者要么慢（每次等满）要么不稳（机器慢时没跑完）。
     */
    private JsonNode awaitFinished(String token, String taskNo) throws Exception {
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MILLIS;
        JsonNode task = null;
        while (System.currentTimeMillis() < deadline) {
            task = readJson(mockMvc.perform(get("/api/admin/excel-tasks/" + taskNo)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("data");
            int status = task.get("status").asInt();
            if (status == 2 || status == 3) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待任务结束超时，最后状态: " + task);
    }

    /**
     * 幂等地准备导入用的分类：整个 JVM 只创建一次（原因见 {@link #categoryCreated}）
     */
    private void ensureCategory(String token) throws Exception {
        if (categoryCreated) {
            return;
        }
        JsonNode json = readJson(mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", 0, "name", CATEGORY_NAME))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        // 创建接口返回 Result<Void>，成功与否只能看业务码——静默失败会让后面每个用例
        // 都以「分类不存在」收场，很难定位到根因
        assertEquals(200, json.get("code").asInt(), () -> "创建分类失败，后续导入用例会连锁失败: " + json);
        categoryCreated = true;
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
