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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员账号管理 + 操作日志查询 + 管理员导入 集成测试
 *
 * <p>覆盖 Controller → Service → Mapper 全链路。初始数据由 {@code AdminDataInitializer} 启动时注入
 * （admin / admin123 + ADMIN 角色 + 全部内置菜单权限）。
 *
 * <p>测试环境无 Redis，用 {@link MockBean} 替换 StringRedisTemplate。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserIntegrationTest {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 与 AdminUserImportServiceImpl.HEADERS 保持一致（读取时表头会被跳过，但保持一致可防将来加表头校验时误报） */
    private static final List<String> IMPORT_HEADERS = List.of(
            "用户名*", "姓名", "手机号", "角色编码*(多个用逗号分隔)", "状态(1启用/0禁用)", "初始密码(留空用默认密码)");

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
    void adminUserApi_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAdminUser_thenQueryListAndDetail_shouldReturnBoundRoles() throws Exception {
        String token = loginAsAdmin();
        Long roleId = createRole(token, "运营", "OPERATOR");

        JsonNode created = readJson(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "operator01",
                                "password", "123456",
                                "realName", "张运营",
                                "roleIds", List.of(roleId)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, created.get("code").asInt());
        long userId = created.get("data").asLong();
        assertTrue(userId > 0);

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/users")
                        .param("username", "operator01")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("records");
        assertEquals(1, records.size());
        assertTrue(records.get(0).get("roleNames").toString().contains("运营"));

        JsonNode detail = readJson(mockMvc.perform(get("/api/admin/users/" + userId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertEquals("张运营", detail.get("realName").asText());
        assertTrue(detail.get("roleIds").toString().contains(String.valueOf(roleId)));
    }

    @Test
    void createAdminUser_withDuplicateUsername_shouldReturnUsernameExists() throws Exception {
        String token = loginAsAdmin();
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "admin", "password", "123456"));

        JsonNode json = readJson(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(5006, json.get("code").asInt());
    }

    @Test
    void disableSelf_shouldBeRejected() throws Exception {
        String token = loginAsAdmin();
        long adminId = currentAdminId(token);

        JsonNode json = readJson(mockMvc.perform(put("/api/admin/users/" + adminId + "/status")
                        .param("status", "0")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(5008, json.get("code").asInt());
    }

    @Test
    void disabledAdmin_shouldLoseAccessImmediately() throws Exception {
        String token = loginAsAdmin();
        Long roleId = createRole(token, "临时运营", "TEMP_OP");
        long userId = createAdminUser(token, "temp01", "123456", roleId);
        String tempToken = login("temp01", "123456");

        // 该角色未分配任何菜单权限：有合法 token 但无权限 → 403，说明方法级鉴权生效
        JsonNode denied = readJson(mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(tempToken)))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString());
        // 必须是「无权限访问（403）」而不是被兜底成「系统内部错误（500）」
        assertEquals(403, denied.get("code").asInt());

        // 禁用后已签发的 token 立即失效，而不是等它自然过期
        mockMvc.perform(put("/api/admin/users/" + userId + "/status")
                        .param("status", "0")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(tempToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_shouldLetAdminLoginWithNewPassword() throws Exception {
        String token = loginAsAdmin();
        Long roleId = createRole(token, "运营", "OPERATOR");
        long userId = createAdminUser(token, "operator01", "123456", roleId);

        mockMvc.perform(put("/api/admin/users/" + userId + "/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk());

        assertNotNull(login("operator01", "newpass123"));
    }

    @Test
    void changeOwnPassword_withWrongOldPassword_shouldReturnOldPasswordError() throws Exception {
        String token = loginAsAdmin();

        JsonNode json = readJson(mockMvc.perform(put("/api/admin/profile/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong\",\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(5007, json.get("code").asInt());
    }

    @Test
    void changeOwnPassword_shouldTakeEffect() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(put("/api/admin/profile/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"admin123\",\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk());

        assertNotNull(login("admin", "newpass123"));
    }

    @Test
    void assignRoles_shouldReplaceRoleBinding() throws Exception {
        String token = loginAsAdmin();
        Long firstRoleId = createRole(token, "角色甲", "ROLE_A");
        Long secondRoleId = createRole(token, "角色乙", "ROLE_B");
        long userId = createAdminUser(token, "operator01", "123456", firstRoleId);

        mockMvc.perform(put("/api/admin/users/" + userId + "/roles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roleIds", List.of(secondRoleId)))))
                .andExpect(status().isOk());

        JsonNode detail = readJson(mockMvc.perform(get("/api/admin/users/" + userId)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertTrue(detail.get("roleIds").toString().contains(String.valueOf(secondRoleId)));
        assertTrue(!detail.get("roleIds").toString().contains(String.valueOf(firstRoleId)));
    }

    @Test
    void assignRoles_withUnknownRole_shouldReturnRoleNotFound() throws Exception {
        String token = loginAsAdmin();
        Long roleId = createRole(token, "运营", "OPERATOR");
        long userId = createAdminUser(token, "operator01", "123456", roleId);

        JsonNode json = readJson(mockMvc.perform(put("/api/admin/users/" + userId + "/roles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[999999]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(5010, json.get("code").asInt());
    }

    @Test
    void operationLog_shouldRecordWriteOperations() throws Exception {
        String token = loginAsAdmin();
        Long roleId = createRole(token, "运营", "OPERATOR");
        createAdminUser(token, "operator01", "123456", roleId);

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/operation-logs")
                        .param("module", "系统管理")
                        .param("operation", "新增管理员")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("records");

        assertTrue(records.size() >= 1);
        JsonNode first = records.get(0);
        assertEquals("新增管理员", first.get("operation").asText());
        assertEquals("admin", first.get("username").asText());
        assertEquals(1, first.get("status").asInt());
        assertTrue(first.get("requestUri").asText().startsWith("/api/admin/users"));
    }

    @Test
    void operationLog_shouldMaskPasswords() throws Exception {
        String token = loginAsAdmin();
        Long roleId = createRole(token, "运营", "OPERATOR");
        createAdminUser(token, "operator01", "123456", roleId);

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/operation-logs")
                        .param("operation", "新增管理员")
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("data").get("records");

        String params = records.get(0).get("requestParams").asText();
        // 审计日志绝不能落明文密码
        assertTrue(params.contains("\"password\":\"***\""));
        assertTrue(!params.contains("123456"));
    }

    @Test
    void importAdminUsers_shouldReportPartialSuccess() throws Exception {
        String token = loginAsAdmin();
        byte[] content = ExcelSupport.write("管理员导入模板", IMPORT_HEADERS, List.of(
                List.of("import01", "导入一", "13800000001", "ADMIN", "1", ""),
                List.of("import02", "导入二", "13800000002", "NO_SUCH_ROLE", "1", "")));

        JsonNode data = readJson(mockMvc.perform(multipart("/api/admin/users/import")
                        .file(new MockMultipartFile("file", "管理员.xlsx", XLSX_CONTENT_TYPE, content))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");

        assertEquals(2, data.get("totalRows").asInt());
        assertEquals(1, data.get("successCount").asInt());
        assertEquals(1, data.get("failCount").asInt());
        assertEquals(3, data.get("errors").get(0).get("rowNum").asInt());
    }

    @Test
    void importAdminUsers_withNonExcelFile_shouldReturnFileInvalid() throws Exception {
        String token = loginAsAdmin();

        JsonNode json = readJson(mockMvc.perform(multipart("/api/admin/users/import")
                        .file(new MockMultipartFile("file", "管理员.txt", MediaType.TEXT_PLAIN_VALUE,
                                "not excel".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(5009, json.get("code").asInt());
    }

    @Test
    void downloadImportTemplate_shouldReturnParseableWorkbook() throws Exception {
        String token = loginAsAdmin();

        byte[] content = mockMvc.perform(get("/api/admin/users/import/template")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(content.length > 0);
        List<ExcelRow> rows = ExcelSupport.read(new ByteArrayInputStream(content), 10);
        assertEquals(1, rows.size());
        assertEquals("operator01", rows.get(0).cell(0));
    }

    @Test
    void operationLogApi_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/operation-logs"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String loginAsAdmin() throws Exception {
        return login("admin", "admin123");
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    private long currentAdminId(String token) throws Exception {
        JsonNode data = readJson(mockMvc.perform(get("/api/admin/auth/me")
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("data");
        return data.get("id").asLong();
    }

    private Long createRole(String token, String name, String code) throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "code", code))))
                .andExpect(status().isOk());

        JsonNode records = readJson(mockMvc.perform(get("/api/admin/roles")
                        .param("name", name)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("data").get("records");
        return records.get(0).get("id").asLong();
    }

    private long createAdminUser(String token, String username, String password, Long roleId) throws Exception {
        JsonNode json = readJson(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "roleIds", List.of(roleId)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, json.get("code").asInt());
        return json.get("data").asLong();
    }
}
