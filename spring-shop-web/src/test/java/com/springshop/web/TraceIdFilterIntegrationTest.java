package com.springshop.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * traceId 链路追踪集成测试
 *
 * <p>验证 TraceIdFilter 全链路行为：
 * <ul>
 *   <li>请求未携带 X-Trace-Id 时，自动生成 traceId 并回写响应头；</li>
 *   <li>请求携带 X-Trace-Id 时，原样透传到响应头，便于跨服务链路串联。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // 每个用例结束回滚数据库，保证用例互不影响
class TraceIdFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);

    @BeforeEach
    void setUpRedisMocks() {
        // 测试环境无 Redis：mock 掉 opsForValue()（与其他集成测试一致）
        Mockito.when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void health_shouldReturnGeneratedTraceIdHeader() throws Exception {
        // 未携带 X-Trace-Id 请求头：过滤器应自动生成非空 traceId 并回写响应头
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String traceId = result.getResponse().getHeader("X-Trace-Id");
                    assertNotNull(traceId, "响应头应包含 X-Trace-Id");
                    assertFalse(traceId.isBlank(), "自动生成的 traceId 不应为空");
                });
    }

    @Test
    void health_shouldEchoIncomingTraceIdHeader() throws Exception {
        // 携带 X-Trace-Id 请求头：过滤器应原样回传同值，保证链路可串联
        mockMvc.perform(get("/api/health").header("X-Trace-Id", "test-trace-123"))
                .andExpect(status().isOk())
                .andExpect(result ->
                        assertEquals("test-trace-123", result.getResponse().getHeader("X-Trace-Id")));
    }
}
