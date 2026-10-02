package com.springshop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.security.JwtTokenProvider;
import com.springshop.user.entity.User;
import com.springshop.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 小程序登录集成测试
 *
 * <p>覆盖「code 换 openid → 首次自动注册 / 再次登录复用 → 签发带 MINIAPP 标识的 token →
 * 携带 token 访问受保护接口」全链路。测试环境未配置小程序凭证，走 mock openid 派生。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // 每个用例结束回滚数据库，保证用例互不影响
class MiniAppAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserMapper userMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void miniAppLogin_newCode_shouldAutoRegisterAndReturnMiniAppToken() throws Exception {
        JsonNode data = miniAppLogin("code-abc");
        String token = data.get("token").asText();
        assertNotNull(token);
        // token 应带 MINIAPP 客户端标识与前台用户类型
        assertEquals("MINIAPP", jwtTokenProvider.getClientId(token));
        assertEquals(JwtTokenProvider.USER_TYPE_USER, jwtTokenProvider.getUserType(token));

        // 自动创建的用户：用户名为 wx_ 前缀，openid 已落库
        JsonNode user = data.get("user");
        assertTrue(user.get("username").asText().startsWith("wx_"));
        User persisted = userMapper.selectById(user.get("id").asLong());
        assertNotNull(persisted);
        assertNotNull(persisted.getOpenid());
    }

    @Test
    void miniAppLogin_sameCodeTwice_shouldReturnSameUser() throws Exception {
        JsonNode first = miniAppLogin("code-dup");
        JsonNode second = miniAppLogin("code-dup");
        // 同一 code（同一 openid）二次登录应复用同一用户，不重复建号
        assertEquals(first.get("user").get("id").asLong(), second.get("user").get("id").asLong());
    }

    @Test
    void miniAppLogin_blankCode_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/miniapp/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(400, json.get("code").asInt());
                });
    }

    @Test
    void miniAppLogin_tokenShouldAccessProtectedApi() throws Exception {
        JsonNode data = miniAppLogin("code-me");
        String token = data.get("token").asText();
        String expectedUsername = data.get("user").get("username").asText();

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(200, json.get("code").asInt());
                    assertEquals(expectedUsername, json.get("data").get("username").asText());
                });
    }

    /**
     * 小程序登录辅助方法：断言登录成功并返回 data 节点
     */
    private JsonNode miniAppLogin(String code) throws Exception {
        String body = mockMvc.perform(post("/api/auth/miniapp/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(200, json.get("code").asInt());
        return json.get("data");
    }
}
