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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel 异步任务中心 + 四类导出 集成测试
 *
 * <p>覆盖：管理员导入（异步）→ 任务进度 → 失败明细下载；商品 / 管理员 / 操作日志 / 订单
 * 四类导出 → 任务中心 → 结果文件下载。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：任务执行体跑在 excel-task 线程池里，
 * 用另一条连接读写数据库。测试事务对它不可见，加了只会得到「任务查不到、进度永远是 0」的假失败。
 * 因此所有测试数据都带 {@link #RUN_TAG} 唯一后缀，且断言按名称过滤、不依赖总数。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExcelTaskIntegrationTest {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final List<String> ADMIN_IMPORT_HEADERS = List.of(
            "用户名*", "姓名", "手机号", "角色编码*(多个用逗号分隔)", "状态(1启用/0禁用)", "初始密码(留空用默认密码)");

    private static final String RUN_TAG = Long.toString(System.nanoTime() % 1_000_000);

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
    void excelTaskApi_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/excel-tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void taskDetail_withUnknownTaskNo_shouldReturnNotFound() throws Exception {
        String token = loginAsAdmin();

        JsonNode json = readJson(mockMvc.perform(get("/api/admin/excel-tasks/NOT-A-REAL-TASK")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // 任务不存在与「不是我的任务」返回同一个码：不给探测他人任务号的机会
        assertEquals(41, json.get("code").asInt());
    }

    /**
     * 管理员导入：合法行入库、非法行只记录原因，任务整体仍算成功，并能下载失败明细
     */
    @Test
    void adminUserImport_shouldReportPartialSuccessAndOfferErrorDetailFile() throws Exception {
        String token = loginAsAdmin();
        byte[] content = ExcelSupport.writeDynamic("管理员导入模板", ADMIN_IMPORT_HEADERS, List.of(
                List.of("import-ok-" + RUN_TAG, "导入一", "13800000001", "ADMIN", "1", ""),
                List.of("import-bad-" + RUN_TAG, "导入二", "13800000002", "NO_SUCH_ROLE", "1", "")));

        JsonNode accepted = readJson(mockMvc.perform(multipart("/api/admin/users/import")
                        .file(new MockMultipartFile("file", "管理员.xlsx", XLSX_CONTENT_TYPE, content))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");

        JsonNode task = awaitFinished(token, accepted.get("taskNo").asText());
        assertEquals(2, task.get("processedRows").asInt());
        assertEquals(1, task.get("successRows").asInt());
        assertEquals(1, task.get("failRows").asInt());
        assertEquals(2, task.get("status").asInt(), "行级失败不改变任务成功状态，详情: " + task);
        assertTrue(task.get("downloadable").asBoolean(), "有失败行时应可下载明细");

        // 失败明细是「按需生成」的视图，不落盘，下载时流式拼出来
        byte[] detail = mockMvc.perform(get("/api/admin/excel-tasks/" + task.get("taskNo").asText() + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(detail),
                ExcelReadOptions.defaults());
        assertEquals(1, rows.size());
        assertEquals(3, Integer.parseInt(rows.get(0).cell(0)), "行号应与 Excel 中一致（表头占第 1 行）");
        assertTrue(rows.get(0).cell(1).contains("NO_SUCH_ROLE"));
    }

    @Test
    void adminUserImport_withNonExcelFile_shouldReturnFileInvalid() throws Exception {
        String token = loginAsAdmin();

        JsonNode json = readJson(mockMvc.perform(multipart("/api/admin/users/import")
                        .file(new MockMultipartFile("file", "管理员.txt", MediaType.TEXT_PLAIN_VALUE,
                                "not excel".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(5009, json.get("code").asInt());
    }

    /**
     * 商品导出（导出选中）：传 ids 时只导出这些商品，且列名与列表页口径一致
     */
    @Test
    void productExport_shouldExportOnlySelectedProducts() throws Exception {
        String token = loginAsAdmin();
        long categoryId = createCategory(token, "导出测试分类-" + RUN_TAG);
        long productId = createProduct(token, categoryId, "导出测试商品-" + RUN_TAG, "SKU-EXPORT-" + RUN_TAG);

        JsonNode task = runExport(token, "/api/admin/products/export",
                "{\"ids\":[" + productId + "]}");

        assertEquals(1, task.get("successRows").asInt(), () -> "按 ids 导出应命中 1 条，详情: " + task);
        assertTrue(task.get("downloadable").asBoolean());

        List<ExcelRow> rows = downloadRows(token, task.get("taskNo").asText());
        assertEquals(1, rows.size());
        assertEquals("导出测试商品-" + RUN_TAG, rows.get(0).cell(1));
        assertEquals("导出测试分类-" + RUN_TAG, rows.get(0).cell(3));
    }

    /**
     * 操作日志导出：先做一次写操作产生审计日志，再按模块筛选导出
     */
    @Test
    void operationLogExport_shouldExportFilteredRows() throws Exception {
        String token = loginAsAdmin();
        String roleName = "导出日志测试角色-" + RUN_TAG;
        createRole(token, roleName, "LOG_EXPORT_" + RUN_TAG);

        JsonNode task = runExport(token, "/api/admin/operation-logs/export",
                "{\"module\":\"系统管理\",\"operation\":\"新增角色\"}");

        assertTrue(task.get("successRows").asInt() >= 1,
                () -> "至少应导出刚产生的那条审计日志，详情: " + task);

        List<ExcelRow> rows = downloadRows(token, task.get("taskNo").asText());
        // 列顺序：日志ID / 操作人 / 模块 / 操作 / ...
        assertTrue(rows.stream().anyMatch(row -> "系统管理".equals(row.cell(2))),
                "导出的行应属于「系统管理」模块");
    }

    /**
     * 订单导出（筛选无命中）：仍然要产出一个「只有表头」的合法文件。
     *
     * <p>这条边界很容易被漏掉：没有数据时如果直接不写文件，用户点下载会拿到 404 或损坏文件，
     * 分不清是「确实没有订单」还是「导出坏了」。
     */
    @Test
    void orderExport_withNoMatchingData_shouldStillProduceHeaderOnlyFile() throws Exception {
        String token = loginAsAdmin();

        JsonNode task = runExport(token, "/api/admin/orders/export",
                "{\"orderNo\":\"NO-SUCH-ORDER-" + RUN_TAG + "\"}");

        assertEquals(0, task.get("successRows").asInt());
        assertEquals(2, task.get("status").asInt());
        assertTrue(task.get("downloadable").asBoolean(), "没有数据也要能下载（至少能拿到表头）");

        byte[] content = download(token, task.get("taskNo").asText());
        assertTrue(content.length > 0);

        // headRowNumber(0)：把表头当数据读回来，验证「表头确实写进去了」
        List<ExcelRow> withHeader = ExcelSupport.readAll(new ByteArrayInputStream(content),
                ExcelReadOptions.defaults().headRowNumber(0));
        assertEquals(1, withHeader.size());
        assertEquals("订单号", withHeader.get(0).cell(1));
    }

    /**
     * 任务中心只返回当前管理员自己的任务，并支持按方向 / 状态筛选
     */
    @Test
    void excelTaskList_shouldReturnOwnTasksWithFilters() throws Exception {
        String token = loginAsAdmin();
        JsonNode task = runExport(token, "/api/admin/orders/export", "{}");
        String taskNo = task.get("taskNo").asText();

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/excel-tasks")
                        .param("taskType", "2")
                        .param("status", "2")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("records");

        assertTrue(records.size() >= 1);
        assertTrue(records.toString().contains(taskNo), "刚提交的导出任务应出现在任务中心");
        // 每条记录都带前端直接可用的派生字段
        JsonNode first = records.get(0);
        assertFalse(first.get("statusName").asText().isBlank());
        assertTrue(first.get("downloadable").asBoolean());
    }

    // ---------- 测试辅助 ----------

    private JsonNode runExport(String token, String path, String body) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post(path)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // 业务失败也是 HTTP 200，只看状态码会把「没受理」当成「已受理」
        assertEquals(200, json.get("code").asInt(), () -> "提交导出任务失败 " + path + ": " + json);
        JsonNode accepted = json.get("data");
        // data 为 JSON null 时 Jackson 给的是 NullNode（非 null 引用），直接 get("taskNo")
        // 会得到一个很难读的空指针，这里先显式判断
        assertFalse(accepted.isNull(), () -> "提交导出任务未返回任务信息 " + path + ": " + json);
        assertTrue(accepted.get("taskNo").asText().startsWith("E"),
                () -> "导出任务号应以 E 开头: " + accepted);
        return awaitFinished(token, accepted.get("taskNo").asText());
    }

    private List<ExcelRow> downloadRows(String token, String taskNo) throws Exception {
        return ExcelSupport.readAll(new ByteArrayInputStream(download(token, taskNo)),
                ExcelReadOptions.defaults());
    }

    private byte[] download(String token, String taskNo) throws Exception {
        return mockMvc.perform(get("/api/admin/excel-tasks/" + taskNo + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

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
     * 建一个分类并返回它的 id
     *
     * <p>{@code POST /api/admin/categories} 返回的是 {@code Result<Void>}，拿不到新建 id，
     * 只能建完再从分类树里按名字找回来。
     *
     * <p>直接对 {@code data} 调 {@code asLong()} 会静默得到 0（JSON null → NullNode → 0），
     * 于是「按 id 导出」就变成「导出 id=0 的商品」：一条都查不到，而且不报任何错。
     */
    private long createCategory(String token, String name) throws Exception {
        JsonNode created = readJson(mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("parentId", 0, "name", name))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, created.get("code").asInt(), () -> "创建分类失败: " + created);

        JsonNode tree = readJson(mockMvc.perform(get("/api/admin/categories/tree")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        for (JsonNode node : tree) {
            if (name.equals(node.get("name").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new AssertionError("分类树里找不到刚创建的分类「" + name + "」: " + tree);
    }

    private long createProduct(String token, long categoryId, String name, String skuCode) throws Exception {
        Map<String, Object> request = Map.of(
                "categoryId", categoryId,
                "name", name,
                "status", 1,
                "skus", List.of(Map.of(
                        "skuCode", skuCode,
                        "specs", "颜色:黑",
                        "price", "199.00",
                        "stock", 10,
                        "status", 1)));
        JsonNode json = readJson(mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, json.get("code").asInt(), () -> "创建商品失败: " + json);
        return json.get("data").asLong();
    }

    private void createRole(String token, String name, String code) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "code", code))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, json.get("code").asInt(), () -> "创建角色失败: " + json);
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
