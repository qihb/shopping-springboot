package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台权限中心集成测试
 *
 * <p>覆盖「管理员登录 → 携带 token 访问后台接口」全链路，以及前后台隔离（越权防护）。
 * 初始数据由 {@code AdminDataInitializer} 启动时注入（admin / admin123 + ADMIN 角色）。
 *
 * <p>测试环境无 Redis，使用 {@link MockBean} 替换 StringRedisTemplate，
 * mock 默认行为恰好满足：失败计数不生效、黑名单不生效，登录链路可跑通。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        // 测试环境无 Redis：mock 掉 opsForValue()，避免 NPE；
        // 默认行为正好满足：get 返回 null（未锁定）、increment 返回 null（不触发过期设置）。
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void adminLogin_shouldReturnTokenWithRolesAndPermissions() throws Exception {
        String body = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertEquals(200, json.get("code").asInt());
        JsonNode data = json.get("data");
        assertNotNull(data.get("token").asText());
        assertEquals("admin", data.get("adminUser").get("username").asText());
        // 超级管理员应带 ADMIN 角色与 system:role:list 权限
        assertTrue(data.get("adminUser").get("roles").toString().contains("ADMIN"));
        assertTrue(data.get("adminUser").get("permissions").toString().contains("system:role:list"));
    }

    @Test
    void adminLogin_wrongPassword_shouldReturnAdminPasswordError() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(5002, json.get("code").asInt());
                });
    }

    @Test
    void adminApi_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/roles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminApi_withAdminToken_shouldSucceed() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(get("/api/admin/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                });
    }

    @Test
    void adminApi_withFrontendUserToken_shouldReturn401() throws Exception {
        // 注册并登录前台用户
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"customer\",\"password\":\"123456\"}"));
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString();
        String userToken = objectMapper.readTree(loginBody).get("data").get("token").asText();

        // 前台用户 token 访问后台接口必须被拒绝（前后台隔离）
        mockMvc.perform(get("/api/admin/roles")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleCreate_withAdminToken_shouldSucceed() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "运营", "code", "OPERATOR", "description", "运营人员"))))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                });
    }

    @Test
    void roleCreate_duplicateCode_shouldReturnCodeExists() throws Exception {
        String token = loginAsAdmin();
        String body = "{\"name\":\"重复角色\",\"code\":\"ADMIN\"}";

        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(5011, json.get("code").asInt());
                });
    }

    /**
     * 管理员登录辅助方法：返回 token
     */
    private String loginAsAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }
}
