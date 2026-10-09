package com.springshop.stats.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.common.result.PageResult;
import com.springshop.stats.dto.StatsTaskLogPageQuery;
import com.springshop.stats.entity.StatsTaskLog;
import com.springshop.stats.mapper.StatsTaskLogMapper;
import com.springshop.stats.task.CartRecallTask;
import com.springshop.stats.vo.StatsTaskLogVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计任务执行日志查询服务单元测试（纯 Mockito，不启动 Spring 容器）
 *
 * <p><b>断言为什么落在「绑定值」而不是 SQL 文本上</b>：这里没有 MP 的 {@code GlobalConfig}，
 * lambda wrapper 渲染出来的可能是属性名而不是列名（详见项目记忆里的踩坑记录）。
 * 而本类真正要钉住的是<b>语义</b>——「结束日期要换算成次日 0 点」「status 为空时不参与过滤」——
 * 这些都能从绑定值上精确断言。至于「列名对不对、SQL 能不能跑」，
 * 交给 {@code AdminTaskLogQueryIntegrationTest} 在真实 H2 上验证。
 */
@ExtendWith(MockitoExtension.class)
class TaskLogQueryServiceImplTest {

    @Mock
    private StatsTaskLogMapper statsTaskLogMapper;

    @InjectMocks
    private TaskLogQueryServiceImpl service;

    /**
     * 预热 MyBatis-Plus 的 Lambda 元数据（沿用 product / cart / order / admin 单测的既有做法）
     *
     * <p>纯 Mockito 环境没有 MP 的全量初始化，{@code LambdaQueryWrapper} 解析列名时会直接抛
     * 「can not find lambda cache for this entity」。必须在打桩前手动初始化。
     */
    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        try {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                    StatsTaskLog.class);
        } catch (Exception ignore) {
            // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
        }
    }

    @Test
    void page_should_defaultToCartRecallTask_whenTaskNameBlank() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        query.setTaskName("   ");
        stubPage(List.of());

        service.page(query);

        assertTrue(boundValues().contains(CartRecallTask.TASK_NAME),
                "taskName 为空/空白时必须回落到默认任务名，否则会把其他任务的记录一起查出来");
    }

    @Test
    void page_should_useGivenTaskName_whenProvided() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        query.setTaskName("order-timeout");
        stubPage(List.of());

        service.page(query);

        Collection<Object> values = boundValues();
        assertTrue(values.contains("order-timeout"));
        assertFalse(values.contains(CartRecallTask.TASK_NAME));
    }

    @Test
    void page_should_notBindStatus_whenNull() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        stubPage(List.of());

        service.page(query);

        // 只有 taskName 一个绑定值 ⇒ status 条件整体没参与，
        // 而不是「status = null」那种在 SQL 里恒为假、结果永远空的写法
        assertEquals(1, boundValues().size());
    }

    @Test
    void page_should_bindStatus_whenProvided() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        query.setStatus(0);
        stubPage(List.of());

        service.page(query);

        assertTrue(boundValues().contains(0), "传了 status 就必须真的参与过滤");
    }

    /**
     * 结束日期必须换算成<b>次日 0 点</b>（半开区间），不能写成当天 23:59:59
     *
     * <p>写成 {@code 23:59:59} 会漏掉当天最后一秒内开始的执行记录；
     * 而定时任务恰好在凌晨触发，运营查「昨天」时最容易踩到这个边界。
     */
    @Test
    void page_should_convertEndDateToNextDayMidnight() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        query.setStartDate(LocalDate.of(2026, 10, 1));
        query.setEndDate(LocalDate.of(2026, 10, 3));
        stubPage(List.of());

        service.page(query);

        Collection<Object> values = boundValues();
        assertTrue(values.contains(LocalDateTime.of(2026, 10, 1, 0, 0)),
                "起始日期应换算成当天 0 点（含当天）");
        assertTrue(values.contains(LocalDateTime.of(2026, 10, 4, 0, 0)),
                "结束日期应换算成次日 0 点；写成当天 23:59:59 会漏掉最后一秒的记录");
        assertFalse(values.contains(LocalDateTime.of(2026, 10, 3, 23, 59, 59)));
    }

    @Test
    void page_should_notBindDateConditions_whenBothDatesNull() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        stubPage(List.of());

        service.page(query);

        // 不传日期 ⇒ 不限时间范围（查全部历史），而不是「查今天」
        assertEquals(1, boundValues().size());
    }

    @Test
    void page_should_mapAllEntityFieldsToVO() {
        StatsTaskLog entity = new StatsTaskLog();
        entity.setId(7L);
        entity.setTaskName("cart-recall");
        entity.setStatDate(LocalDate.of(2026, 10, 8));
        entity.setStatus(0);
        entity.setStartTime(LocalDateTime.of(2026, 10, 9, 4, 0));
        entity.setEndTime(LocalDateTime.of(2026, 10, 9, 4, 0, 3));
        entity.setDurationMs(3210L);
        entity.setRowCount(88);
        entity.setErrorMsg("Redis 连接失败");
        entity.setCreateTime(LocalDateTime.of(2026, 10, 9, 4, 0, 3));
        stubPage(List.of(entity));

        PageResult<StatsTaskLogVO> result = service.page(new StatsTaskLogPageQuery());

        assertEquals(1, result.getRecords().size());
        StatsTaskLogVO vo = result.getRecords().get(0);
        // 失败原因与耗时是排查任务时最核心的两个字段，少任何一个这个接口就没意义
        assertEquals(7L, vo.getId().longValue());
        assertEquals("cart-recall", vo.getTaskName());
        assertEquals(LocalDate.of(2026, 10, 8), vo.getStatDate());
        assertEquals(0, vo.getStatus().intValue());
        assertEquals(LocalDateTime.of(2026, 10, 9, 4, 0), vo.getStartTime());
        assertEquals(LocalDateTime.of(2026, 10, 9, 4, 0, 3), vo.getEndTime());
        assertEquals(3210L, vo.getDurationMs().longValue());
        assertEquals(88, vo.getRowCount().intValue());
        assertEquals("Redis 连接失败", vo.getErrorMsg());
        assertEquals(LocalDateTime.of(2026, 10, 9, 4, 0, 3), vo.getCreateTime());
    }

    @Test
    void page_should_passThroughPagingMetadata() {
        StatsTaskLogPageQuery query = new StatsTaskLogPageQuery();
        query.setCurrent(2L);
        query.setSize(5L);
        when(statsTaskLogMapper.selectPage(any(), any())).thenReturn(new Page<>(2, 5, 13));

        PageResult<StatsTaskLogVO> result = service.page(query);

        assertEquals(2L, result.getCurrent().longValue());
        assertEquals(5L, result.getSize().longValue());
        assertEquals(13L, result.getTotal().longValue());
        assertEquals(3L, result.getPages().longValue());
        assertTrue(result.getRecords().isEmpty());
    }

    private void stubPage(List<StatsTaskLog> records) {
        Page<StatsTaskLog> page = new Page<>(1, 10, records.size());
        page.setRecords(records);
        when(statsTaskLogMapper.selectPage(any(), any())).thenReturn(page);
    }

    /**
     * 取出传给 Mapper 的 wrapper 上所有绑定值
     *
     * <p>每个用例只调用一次：{@code verify} 默认校验「恰好一次」，
     * 重复调用会误报「调用次数不符」。
     */
    @SuppressWarnings("unchecked")
    private Collection<Object> boundValues() {
        ArgumentCaptor<Wrapper<StatsTaskLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(statsTaskLogMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<StatsTaskLog> wrapper = (LambdaQueryWrapper<StatsTaskLog>) captor.getValue();
        // ⚠️ 必须先渲染一次 SQL 段：MP 3.5 把参数绑定写成惰性 lambda，
        // 不调 getSqlSegment() 的话 paramNameValuePairs 永远是空的（看起来像「一个条件都没加」）
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }
}
