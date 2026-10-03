package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 商品导入的「真实 HTTP」集成测试
 *
 * <p><b>为什么需要单独一个类</b>：其余集成测试都是 {@code @AutoConfigureMockMvc} + MockMvc，
 * 而 MockMvc 的 {@code multipart()} 是**伪造**请求 —— 它构造一个
 * {@code MockMultipartHttpServletRequest}，直接跳过 Servlet 容器的 multipart 解析。
 * 这导致两条真实路径从未被覆盖：
 * <ol>
 *   <li>Tomcat 真实的 multipart 解析与文件流处理；</li>
 *   <li>{@code spring.servlet.multipart.max-file-size} 的**容器级**拒绝，
 *       以及它究竟以什么形式返回给客户端。</li>
 * </ol>
 *
 * <p>本类用 {@link SpringBootTest.WebEnvironment#RANDOM_PORT} 启动**真实 Tomcat**，
 * 通过 {@link TestRestTemplate} 发真实 HTTP 请求，补上这两条路径。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：请求在 Tomcat 的工作线程里执行，
 * 测试方法的事务无法回滚服务端事务，加了只会给出「已经回滚」的错觉。
 * 因此本类除 {@link #importOverRealMultipart_shouldCreateProductAndSkus()} 外
 * **只做只读或必然失败的操作**；那一个用例会写库，所以它使用**全局唯一**的分类名与商品名
 * （避免与其它测试类冲突），且不依赖任何「总数」断言 —— 因为
 * {@code jdbc:h2:mem:spring_shop_test} 在同一个 surefire JVM 内是**共享**的，
 * 而其它测试类的计数断言都是按 keyword / 角色过滤的，不会被这条附加数据影响。
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

        // 真的能被 POI 解析回来，说明流没有在真实 HTTP 传输中被破坏
        List<ExcelRow> rows = ExcelSupport.read(new ByteArrayInputStream(content), 10);
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).cell(0), rows.get(1).cell(0));
    }

    /**
     * 真实 multipart：上传一份 xlsx，验证容器解析 + Excel 解析 + 业务校验全链路。
     *
     * <p>用「不存在的分类」让所有行失败 —— 这样既能证明文件确实被解析了，
     * 又不会往共享的 H2 库里写数据。
     */
    @Test
    void importOverRealMultipart_shouldParseUploadedWorkbook() throws Exception {
        String token = loginAsAdmin();
        byte[] workbook = ExcelSupport.write("商品导入模板", IMPORT_HEADERS, List.of(
                List.of("真实HTTP测试商品", "副标题", "", "不存在的分类XYZ",
                        "SKU-MULTIPART-001", "", "99.00", "", "1", "1")));

        ResponseEntity<String> response = postWorkbook(token, workbook, "商品.xlsx");

        assertEquals(200, response.getStatusCode().value());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(200, json.get("code").asInt(), "响应体: " + response.getBody());

        JsonNode data = json.get("data");
        assertEquals(1, data.get("totalRows").asInt(), "容器应真实解析出 1 行数据");
        assertEquals(0, data.get("productCount").asInt());
        assertEquals(1, data.get("failRowCount").asInt());
        assertTrue(data.get("errors").get(0).get("message").asText().contains("不存在"),
                "错误信息应指向分类不存在，实际: " + data.get("errors"));
    }

    /**
     * 真实 HTTP 下上传非 Excel 文件 → 2020
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
     * <p>这是本类存在的主要理由 —— MockMvc 完全无法覆盖这条路径。
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
     *
     * <p>其余用例都只验证「解析 + 校验失败」，落库路径此前只被 MockMvc 覆盖过。
     * 既然 MockMvc 的 multipart 不可信，成功路径也值得用真实 HTTP 守一遍。
     *
     * <p>用全局唯一的分类名 / 商品名，避免污染共享 H2 里其它测试类的数据。
     */
    @Test
    void importOverRealMultipart_shouldCreateProductAndSkus() throws Exception {
        String token = loginAsAdmin();
        String categoryName = "多部分上传测试分类";
        String productName = "多部分上传测试商品";
        createCategory(token, categoryName);

        byte[] workbook = ExcelSupport.write("商品导入模板", IMPORT_HEADERS, List.of(
                List.of(productName, "副标题", "https://example.com/mp.jpg", categoryName,
                        "SKU-MULTIPART-101", "颜色:黑", "199.00", "299.00", "10", "1"),
                List.of(productName, "副标题", "https://example.com/mp.jpg", categoryName,
                        "SKU-MULTIPART-102", "颜色:白", "299.00", "", "20", "1")));

        ResponseEntity<String> response = postWorkbook(token, workbook, "商品.xlsx");
        assertEquals(200, response.getStatusCode().value());

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertEquals(2, data.get("totalRows").asInt());
        assertEquals(1, data.get("productCount").asInt(), "同名两行应聚合为一个 SPU");
        assertEquals(2, data.get("skuCount").asInt());
        assertEquals(0, data.get("failRowCount").asInt(), "错误: " + data.get("errors"));

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

    private void createCategory(String token, String name) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/admin/categories",
                new HttpEntity<>("{\"parentId\":0,\"name\":\"" + name + "\"}", headers),
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

    private String loginAsAdmin() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/admin/auth/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin123\"}",
                        jsonHeaders()),
                String.class);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(200, json.get("code").asInt(), "登录失败: " + response.getBody());
        return json.get("data").get("token").asText();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
