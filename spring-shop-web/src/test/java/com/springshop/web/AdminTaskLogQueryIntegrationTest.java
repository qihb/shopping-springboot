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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定时任务执行日志查询 集成测试
 *
 * <p>这条链路补的是「{@code stats_task_log} 有写无读」的缺口：定时任务每天把执行结果写进表，
 * 但此前没有任何查询接口，运营无法回答「今天跑没跑、产出多少行、有没有失败」。
 *
 * <p>断言全部走真实 HTTP + 真实 H2，不 mock Service —— 本接口的价值恰恰在 SQL 过滤语义
 * （时间区间是否含结束当天、status 是否真的参与过滤、排序是否稳定），这些在 mock 掉
 * Mapper 的单测里验证不了。
 *
 * <p>测试环境无 Redis，用 {@link MockBean} 替换 StringRedisTemplate。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminTaskLogQueryIntegrationTest {

    private static final String URL = "/api/admin/stats/task-logs";

    /** 与 {@code CartRecallTask.TASK_NAME} 一致；这里刻意写字面量，任务名改了这条用例就会红 */
    private static final String RECALL_TASK = "cart-recall";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void taskLogApi_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    /**
     * 不传 taskName 时默认只查 cart-recall —— 这是运营最常用的入口
     * （打开页面就想知道「圈人任务今天跑了没」），而不是看到所有任务的混合记录。
     */
    @Test
    void page_should_returnSeededExecution_andDefaultToRecallTask() throws Exception {
        String token = loginAsAdmin();
        LocalDate today = LocalDate.now();
        seed(RECALL_TASK, today, 1, today.atTime(4, 0), 1234L, 88, null);

        JsonNode data = page(token, null, null, null, null);

        assertEquals(1, data.get("total").asInt());
        JsonNode first = data.get("records").get(0);
        assertEquals(RECALL_TASK, first.get("taskName").asText());
        assertEquals(1, first.get("status").asInt());
        assertEquals(88, first.get("rowCount").asInt());
        assertEquals(1234L, first.get("durationMs").asLong());
        assertTrue(first.get("errorMsg").isNull(), "成功记录不该带失败原因");
        assertEquals(today.toString(), first.get("statDate").asText());
    }

    @Test
    void page_should_notLeakOtherTasks_whenTaskNameOmitted() throws Exception {
        String token = loginAsAdmin();
        LocalDate today = LocalDate.now();
        seed("order-timeout", today, 1, today.atTime(1, 0), 10L, 3, null);

        assertEquals(0, page(token, null, null, null, null).get("total").asInt(),
                "不传 taskName 时默认只查 cart-recall，不能把其他任务的记录一起返回");

        JsonNode explicit = page(token, "order-timeout", null, null, null);
        assertEquals(1, explicit.get("total").asInt(), "显式指定 taskName 时必须能查到");
    }

    @Test
    void page_should_filterByStatus_andExposeFailureReason() throws Exception {
        String token = loginAsAdmin();
        LocalDate today = LocalDate.now();
        seed(RECALL_TASK, today, 1, today.atTime(4, 0), 1000L, 50, null);
        seed(RECALL_TASK, today, 0, today.atTime(4, 30), 900L, null, "Redis 连接失败");

        assertEquals(2, page(token, RECALL_TASK, null, null, null).get("total").asInt());

        JsonNode failed = page(token, RECALL_TASK, 0, null, null);
        assertEquals(1, failed.get("total").asInt());
        JsonNode record = failed.get("records").get(0);
        assertEquals(0, record.get("status").asInt());
        assertTrue(record.get("errorMsg").asText().contains("Redis"),
                () -> "失败原因必须透出，否则这个接口对排查没有价值，实际: " + record);
    }

    /**
     * 结束日期必须<b>含当天</b>，且不能把次日 0 点整的记录算进来
     *
     * <p>这是最容易写错的一处：闭区间写成 {@code endDate 23:59:59} 会漏掉最后一秒，
     * 而定时任务正好在凌晨触发，运营查「昨天」时最容易踩到。
     */
    @Test
    void page_should_treatEndDateAsInclusive_withoutBleedingIntoNextDay() throws Exception {
        String token = loginAsAdmin();
        LocalDate today = LocalDate.now();
        seed(RECALL_TASK, today.minusDays(1), 1, today.minusDays(1).atTime(12, 0), 1L, 1, null);
        seed(RECALL_TASK, today, 1, today.atTime(12, 0), 1L, 2, null);
        // 次日 0 点整：任何「含当天」的实现都不该把它算进 [今天, 今天]
        seed(RECALL_TASK, today.plusDays(1), 1, today.plusDays(1).atStartOfDay(), 1L, 3, null);

        assertEquals(1, page(token, RECALL_TASK, null, today, today).get("total").asInt(),
                "结束日期必须含当天，且不能把次日 0 点整的记录算进来");
        assertEquals(2, page(token, RECALL_TASK, null, today.minusDays(1), today).get("total").asInt(),
                "跨天区间应包含两端当天");
        assertEquals(3, page(token, RECALL_TASK, null, null, null).get("total").asInt(),
                "不传日期时不应限时间范围");
    }

    @Test
    void page_should_orderByStartTimeDesc() throws Exception {
        String token = loginAsAdmin();
        LocalDate today = LocalDate.now();
        seed(RECALL_TASK, today, 1, today.atTime(1, 0), 1L, 1, null);
        seed(RECALL_TASK, today, 1, today.atTime(5, 0), 1L, 5, null);
        seed(RECALL_TASK, today, 1, today.atTime(3, 0), 1L, 3, null);

        JsonNode records = page(token, RECALL_TASK, null, null, null).get("records");

        assertEquals(3, records.size());
        // 最近一次执行排最前：运营打开页面先看「刚刚那次跑成什么样」
        assertEquals(5, records.get(0).get("rowCount").asInt());
        assertEquals(3, records.get(1).get("rowCount").asInt());
        assertEquals(1, records.get(2).get("rowCount").asInt());
    }

    /**
     * 分页参数名写错必须明确报错
     *
     * <p>上一轮加了 {@code PageParamGuardInterceptor}，本接口绑定 {@code PageQuery} 子类
     * 因而自动受保护。这里钉住「新接口确实被守卫覆盖」，否则前端写 {@code pageNo} 会
     * 静默拿到第一页。
     */
    @Test
    void page_withWrongPaginationParamName_shouldFailLoudly() throws Exception {
        String token = loginAsAdmin();

        String body = mockMvc.perform(get(URL)
                        .header("Authorization", "Bearer " + token)
                        .param("pageNo", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertEquals(400, json.get("code").asInt(), () -> "写错分页参数名必须报错，实际响应: " + json);
        assertTrue(json.get("message").asText().contains("pageNo"));
        assertTrue(json.get("message").asText().contains("current"));
    }

    // ------------------------------------------------------------------ 辅助

    private void seed(String taskName, LocalDate statDate, int status, LocalDateTime startTime,
                      Long durationMs, Integer rowCount, String errorMsg) {
        jdbcTemplate.update("INSERT INTO stats_task_log "
                        + "(task_name, stat_date, status, start_time, end_time, duration_ms, row_count, error_msg) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskName, Date.valueOf(statDate), status,
                Timestamp.valueOf(startTime), Timestamp.valueOf(startTime.plusSeconds(1)),
                durationMs, rowCount, errorMsg);
    }

    /** 统一用 size=50 查询，避免断言被默认分页截断 */
    private JsonNode page(String token, String taskName, Integer status,
                          LocalDate startDate, LocalDate endDate) throws Exception {
        MockHttpServletRequestBuilder request = get(URL)
                .header("Authorization", "Bearer " + token)
                .param("size", "50");
        if (taskName != null) {
            request = request.param("taskName", taskName);
        }
        if (status != null) {
            request = request.param("status", String.valueOf(status));
        }
        if (startDate != null) {
            request = request.param("startDate", startDate.toString());
        }
        if (endDate != null) {
            request = request.param("endDate", endDate.toString());
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
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
