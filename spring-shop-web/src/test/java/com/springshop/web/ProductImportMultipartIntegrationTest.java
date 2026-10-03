package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ExcelReadOptions;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品导入 / 导出任务的「真实 HTTP」集成测试
 *
 * <p><b>为什么需要单独一个类</b>：其余集成测试都是 {@code @AutoConfigureMockMvc} + MockMvc，
 * 而 MockMvc 的 {@code multipart()} 是**伪造**请求 —— 它构造一个
 * {@code MockMultipartHttpServletRequest}，直接跳过 Servlet 容器的 multipart 解析。
 * 这导致几条真实路径从未被覆盖：
 * <ol>
 *   <li>Tomcat 真实的 multipart 解析与文件流处理；</li>
 *   <li>{@code spring.servlet.multipart.max-file-size} 的**容器级**拒绝，
 *       以及它究竟以什么形式返回给客户端；</li>
 *   <li>任务结果文件的**真实流式下载**（Controller 直接写 {@code HttpServletResponse}，
 *       而不是返回 {@code ResponseEntity<byte[]>}）。</li>
 * </ol>
 *
 * <p>本类用 {@link SpringBootTest.WebEnvironment#RANDOM_PORT} 启动**真实 Tomcat**，
 * 通过 {@link TestRestTemplate} 发真实 HTTP 请求，补上这些路径。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：请求在 Tomcat 的工作线程里执行，
 * 测试方法的事务无法回滚服务端事务，加了只会给出「已经回滚」的错觉。
 * 因此本类写入的数据一律使用**全局唯一**的名称 / 编码（避免与其它测试类冲突），
 * 且不依赖任何「总数」断言 —— 因为 {@code jdbc:h2:mem:spring_shop_test}
 * 在同一个 surefire JVM 内是**共享**的，而其它测试类的计数断言都是按 keyword / 角色过滤的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductImportMultipartIntegrationTest {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final List<String> IMPORT_HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    /** 大于 spring.servlet.multipart.max-file-size（5MB） */
    private static final int OVERSIZED_BYTES = 6 * 1024 * 1024;

    private static final String RUN_TAG = Long.toString(System.nanoTime() % 1_000_000);

    private static final long TASK_TIMEOUT_MILLIS = 30_000;

    @Autowired
    private TestRestTemplate restTemplate;

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

    /**
     * 模板下载：真实 HTTP 下的响应头与内容
     */
    @Test
    void downloadTemplate_overRealHttp_shouldReturnXlsxAttachment() throws Exception {
        String token = loginAsAdmin();

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/admin/products/import/template",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                byte[].class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(XLSX_CONTENT_TYPE, response.getHeaders().getContentType().toString());

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("attachment"),
                "应带 attachment 头，实际: " + disposition);

        byte[] content = response.getBody();
        assertTrue(content != null && content.length > 0, "模板内容不应为空");

        // 真的能被 Fesod 解析回来，说明流没有在真实 HTTP 传输中被破坏
        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(content),
                ExcelReadOptions.defaults());
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).cell(0), rows.get(1).cell(0));
    }

    /**
     * 真实 multipart：上传一份 xlsx，验证容器解析 + 异步执行 + 轮询全链路。
     *
     * <p>用「不存在的分类」让所有行失败 —— 这样既能证明文件确实被解析了，
     * 又不会往共享的 H2 库里写商品数据。
     */
    @Test
    void importOverRealMultipart_shouldParseUploadedWorkbook() throws Exception {
        String token = loginAsAdmin();
        byte[] workbook = ExcelSupport.writeDynamic("商品导入模板", IMPORT_HEADERS, List.of(
                List.of("真实HTTP测试商品-" + RUN_TAG, "副标题", "", "不存在的分类XYZ-" + RUN_TAG,
                        "SKU-MULTIPART-001-" + RUN_TAG, "", "99.00", "", "1", "1")));

        ResponseEntity<String> response = postWorkbook(token, workbook, "商品.xlsx");

        assertEquals(200, response.getStatusCode().value());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(200, json.get("code").asInt(), "响应体: " + response.getBody());

        JsonNode task = awaitFinished(token, json.get("data").get("taskNo").asText());
        assertEquals(1, task.get("processedRows").asInt(), "容器应真实解析出 1 行数据");
        assertEquals(0, task.get("successRows").asInt());
        assertEquals(1, task.get("failRows").asInt());
    }

    /**
     * 真实 HTTP 下上传非 Excel 文件 → 2020（受理阶段即拒绝，不产生异步任务）
     */
    @Test
    void importNonExcelOverRealMultipart_shouldReturnFileInvalid() throws Exception {
        String token = loginAsAdmin();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedByteArrayResource("not excel".getBytes(), "商品.csv"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/admin/products/import",
                new HttpEntity<>(body, multipartHeaders(token)),
                String.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2020, objectMapper.readTree(response.getBody()).get("code").asInt());
    }

    /**
     * 超过 max-file-size 的文件：验证**容器级**拒绝会以什么形式返回给客户端。
     *
     * <p>这是本类存在的主要理由之一 —— MockMvc 完全无法覆盖这条路径。
     * 期望是拿到可读的「上传文件过大」提示（由
     * {@code GlobalExceptionHandler.handleMaxUploadSizeExceeded} 提供）。
     */
    @Test
    void importOversizedFile_overRealHttp_shouldReturnReadableMessage() throws Exception {
        String token = loginAsAdmin();
        byte[] oversized = new byte[OVERSIZED_BYTES];

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedByteArrayResource(oversized, "超大商品.xlsx"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/admin/products/import",
                new HttpEntity<>(body, multipartHeaders(token)),
                String.class);

        // 若这里拿到非 200，说明容器在解析阶段就中断了请求，
        // GlobalExceptionHandler 的友好提示根本没机会返回给调用方
        assertEquals(200, response.getStatusCode().value(),
                "超大文件未走到 GlobalExceptionHandler，实际状态: " + response.getStatusCode()
                        + "，响应体: " + response.getBody());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(400, json.get("code").asInt());
        assertTrue(json.get("message").asText().contains("上传文件过大"),
                "应返回可读提示，实际: " + json.get("message"));
    }

    /**
     * 真实 multipart 的**成功路径**：一个 SPU + 两个 SKU 落库，并能在后台列表查到。
     */
    @Test
    void importOverRealMultipart_shouldCreateProductAndSkus() throws Exception {
        String token = loginAsAdmin();
        String categoryName = "多部分上传测试分类-" + RUN_TAG;
        String productName = "多部分上传测试商品-" + RUN_TAG;
        createCategory(token, categoryName);

        byte[] workbook = ExcelSupport.writeDynamic("商品导入模板", IMPORT_HEADERS, List.of(
                List.of(productName, "副标题", "https://example.com/mp.jpg", categoryName,
                        "SKU-MULTIPART-101-" + RUN_TAG, "颜色:黑", "199.00", "299.00", "10", "1"),
                List.of(productName, "副标题", "https://example.com/mp.jpg", categoryName,
                        "SKU-MULTIPART-102-" + RUN_TAG, "颜色:白", "299.00", "", "20", "1")));

        ResponseEntity<String> response = postWorkbook(token, workbook, "商品.xlsx");
        assertEquals(200, response.getStatusCode().value());

        JsonNode task = awaitFinished(token,
                objectMapper.readTree(response.getBody()).get("data").get("taskNo").asText());
        assertEquals(2, task.get("processedRows").asInt());
        assertEquals(2, task.get("successRows").asInt(), "同名两行应聚合为一个 SPU + 两个 SKU");
        assertEquals(0, task.get("failRows").asInt(), "任务详情: " + task);

        // 落库结果能通过后台列表查到（真实 HTTP 读回）
        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/admin/products?keyword=" + productName,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);
        JsonNode records = objectMapper.readTree(listResponse.getBody())
                .get("data").get("records");
        assertEquals(1, records.size());
        assertTrue(records.get(0).toString().contains(productName));
    }

    /**
     * 真实 HTTP 下的**导出结果下载**：验证「先落盘、后流式下载」这条路走得通。
     *
     * <p>Controller 直接写 {@code HttpServletResponse} 而不是返回
     * {@code ResponseEntity<byte[]>}（后者会把整个文件读进堆内存），
     * 这条路径只有真实 HTTP 才能覆盖到响应头与流内容。
     */
    @Test
    void exportOverRealHttp_shouldStreamDownloadableXlsx() throws Exception {
        String token = loginAsAdmin();
        long adminId = currentAdminId(token);

        // 只导出「当前登录的这个管理员」，结果可预期且不依赖其它测试留下的数据
        ResponseEntity<String> exportResponse = restTemplate.postForEntity(
                "/api/admin/users/export",
                new HttpEntity<>("{\"ids\":[" + adminId + "]}", bearerJsonHeaders(token)),
                String.class);
        assertEquals(200, exportResponse.getStatusCode().value());

        JsonNode accepted = objectMapper.readTree(exportResponse.getBody()).get("data");
        assertTrue(accepted.get("taskNo").asText().startsWith("E"), "导出任务号应以 E 开头");

        JsonNode task = awaitFinished(token, accepted.get("taskNo").asText());
        assertEquals(2, task.get("status").asInt(), "任务详情: " + task);
        assertEquals(1, task.get("successRows").asInt());
        assertTrue(task.get("downloadable").asBoolean());

        ResponseEntity<byte[]> download = restTemplate.exchange(
                "/api/admin/excel-tasks/" + task.get("taskNo").asText() + "/download",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                byte[].class);

        assertEquals(200, download.getStatusCode().value());
        assertEquals(XLSX_CONTENT_TYPE, download.getHeaders().getContentType().toString());
        String disposition = download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("attachment"),
                "应带 attachment 头，实际: " + disposition);

        // 真实 HTTP 传回来的字节流仍然是一个可解析的工作簿
        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(download.getBody()),
                ExcelReadOptions.defaults());
        assertEquals(1, rows.size());
        assertEquals("admin", rows.get(0).cell(1));
    }

    // ---------- 测试辅助 ----------

    private JsonNode awaitFinished(String token, String taskNo) throws Exception {
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MILLIS;
        JsonNode task = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/admin/excel-tasks/" + taskNo,
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(token)),
                    String.class);
            task = objectMapper.readTree(response.getBody()).get("data");
            int status = task.get("status").asInt();
            if (status == 2 || status == 3) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待任务结束超时，最后状态: " + task);
    }

    private long currentAdminId(String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        return objectMapper.readTree(response.getBody()).get("data").get("id").asLong();
    }

    private void createCategory(String token, String name) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/admin/categories",
                new HttpEntity<>("{\"parentId\":0,\"name\":\"" + name + "\"}", bearerJsonHeaders(token)),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "建分类失败: " + response.getBody());
    }

    private ResponseEntity<String> postWorkbook(String token, byte[] workbook, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedByteArrayResource(workbook, filename));
        return restTemplate.postForEntity(
                "/api/admin/products/import",
                new HttpEntity<>(body, multipartHeaders(token)),
                String.class);
    }

    private HttpHeaders multipartHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders bearerJsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private String loginAsAdmin() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/admin/auth/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin123\"}", headers),
                String.class);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(200, json.get("code").asInt(), "登录失败: " + response.getBody());
        return json.get("data").get("token").asText();
    }

    /**
     * {@link ByteArrayResource} 默认没有文件名，multipart 会退化成非文件字段，
     * 必须覆写 {@code getFilename()} 才能让服务端按「上传文件」处理。
     */
    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
