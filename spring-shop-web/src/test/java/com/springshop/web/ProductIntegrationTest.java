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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductIntegrationTest {

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
    void admin_category_tree_should_require_login() throws Exception {
        mockMvc.perform(get("/api/admin/categories/tree")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void app_category_tree_should_public() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/categories/tree")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        assertNotNull(json);
        assertEquals(200, json.get("code").asInt());
    }

    @Test
    void admin_product_page_should_require_login() throws Exception {
        mockMvc.perform(get("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void app_product_page_should_public() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/products")
                        .param("current", "1")
                        .param("size", "5")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        assertNotNull(json);
        assertEquals(200, json.get("code").asInt());
    }

    /**
     * 分页参数名写错时必须明确报错，而不是静默返回第一页
     *
     * <p>这是原始缺陷的复现：{@code ?pageNo=3&pageSize=2} 曾经返回 HTTP 200 与
     * {@code current=1, size=10}——调用方以为在翻第 3 页，拿到的却是第 1 页，
     * 而响应体看起来完全正常，没有任何信号。
     */
    @Test
    void app_product_page_withWrongPaginationParamName_shouldFailLoudly() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/products")
                        .param("pageNo", "3")
                        .param("pageSize", "2")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        assertEquals(400, json.get("code").asInt(), () -> "写错分页参数名必须报错，实际响应: " + json);
        assertTrue(json.get("message").asText().contains("pageNo"),
                () -> "提示要点出到底是哪个参数名不对，实际: " + json);
        assertTrue(json.get("message").asText().contains("current"),
                () -> "提示要给出正确的参数名，运营/前端才能自己改，实际: " + json);
    }

    /**
     * 守卫只作用于「用 PageQuery 接收分页参数」的接口
     *
     * <p>{@code page} / {@code limit} 在别的接口上可能是合法参数——统计模块的选品接口
     * 就真的用 {@code limit} 表示「取前 N 条」。所以不能全局拉黑，这条用例钉住「不误伤」。
     */
    @Test
    void nonPagedEndpoint_shouldNotBeBlockedByPaginationParamNames() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/categories/tree")
                        .param("page", "2")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("code").asInt(),
                () -> "该接口不用 PageQuery，不该被分页参数名守卫拦截，实际响应: " + json);
    }
}
