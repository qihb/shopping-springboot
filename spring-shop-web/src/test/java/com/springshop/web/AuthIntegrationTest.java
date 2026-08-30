package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与用户接口集成测试
 *
 * <p>覆盖「注册 → 登录 → 携带 token 访问受保护接口」全链路，
 * 使用 H2 内存库 + Flyway 自动建表，不依赖本地 MySQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // 每个用例结束回滚数据库，保证用例互不影响
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void register_shouldSucceed() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123456\",\"nickname\":\"爱丽丝\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                });
    }

    @Test
    void register_duplicateUsername_shouldReturnUsernameExists() throws Exception {
        // 先注册成功
        register("alice");
        // 重复注册应返回 1001
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(1001, json.get("code").asInt());
                });
    }

    @Test
    void register_invalidPhone_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"123456\",\"phone\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(400, json.get("code").asInt());
                });
    }

    @Test
    void login_shouldReturnTokenAndUser() throws Exception {
        register("alice");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                    JsonNode data = json.get("data");
                    assertNotNull(data.get("token").asText());
                    assertEquals("alice", data.get("user").get("username").asText());
                    // 出参不应泄露密码字段
                    assertEquals(false, data.get("user").has("password"));
                });
    }

    @Test
    void login_wrongPassword_shouldReturnPasswordError() throws Exception {
        register("alice");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(1003, json.get("code").asInt());
                });
    }

    @Test
    void me_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullFlow_register_login_thenAccessMe() throws Exception {
        register("alice");

        // 登录获取 token
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginBody).get("data").get("token").asText();

        // 携带 token 访问受保护接口
        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                    assertEquals("alice", json.get("data").get("username").asText());
                });
    }

    /**
     * 注册辅助方法：仅断言注册成功
     */
    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                });
    }
}
