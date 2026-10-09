package com.springshop.stats.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.stats.dto.StatsTaskLogPageQuery;
import com.springshop.stats.entity.StatsTaskLog;
import com.springshop.stats.mapper.StatsTaskLogMapper;
import com.springshop.stats.service.TaskLogQueryService;
import com.springshop.stats.task.CartRecallTask;
import com.springshop.stats.vo.StatsTaskLogVO;
import com.springshop.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * 统计任务执行日志查询服务实现
 *
 * <p>纯只读：不写库、不碰 Redis、不需要事务。
 */
@Service
public class TaskLogQueryServiceImpl implements TaskLogQueryService {

    private final StatsTaskLogMapper statsTaskLogMapper;

    public TaskLogQueryServiceImpl(StatsTaskLogMapper statsTaskLogMapper) {
        this.statsTaskLogMapper = statsTaskLogMapper;
    }

    @Override
    public PageResult<StatsTaskLogVO> page(StatsTaskLogPageQuery query) {
        // 默认只查召回圈人任务：目前 stats_task_log 只有它一个写入方，
        // 但列表接口不该默认「查全部任务」——将来加了第二个任务，
        // 运营看到的是混在一起的记录，而不是他想看的那一个
        String taskName = StringUtils.hasText(query.getTaskName())
                ? query.getTaskName()
                : CartRecallTask.TASK_NAME;

        Page<StatsTaskLog> page = statsTaskLogMapper.selectPage(query.toPage(),
                filterWrapper(taskName, query.getStatus(), query.getStartDate(), query.getEndDate())
                        // 最近一次执行排最前；id 兜底保证同秒记录的顺序稳定（分页不会串行）
                        .orderByDesc(StatsTaskLog::getStartTime)
                        .orderByDesc(StatsTaskLog::getId));

        Page<StatsTaskLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return PageResult.of(voPage);
    }

    /**
     * 组装筛选条件
     *
     * <p>日期用<b>半开区间</b> {@code [startDate 00:00, endDate+1 00:00)}，
     * 而不是 {@code endDate 23:59:59.999999}：后者要猜列的精度（这里是 DATETIME，
     * 无小数秒），写成 23:59:59 会漏掉 23:59:59.x 的记录，写成 .999999 又依赖精度假设。
     * 半开区间与精度无关，且「结束日期含当天」正是运营的直觉。
     */
    private LambdaQueryWrapper<StatsTaskLog> filterWrapper(String taskName, Integer status,
                                                           LocalDate startDate, LocalDate endDate) {
        LocalDateTime from = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime toExclusive = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        return Wrappers.<StatsTaskLog>lambdaQuery()
                .eq(StatsTaskLog::getTaskName, taskName)
                .eq(status != null, StatsTaskLog::getStatus, status)
                .ge(from != null, StatsTaskLog::getStartTime, from)
                .lt(toExclusive != null, StatsTaskLog::getStartTime, toExclusive);
    }

    private StatsTaskLogVO toVO(StatsTaskLog log) {
        StatsTaskLogVO vo = new StatsTaskLogVO();
        vo.setId(log.getId());
        vo.setTaskName(log.getTaskName());
        vo.setStatDate(log.getStatDate());
        vo.setStatus(log.getStatus());
        vo.setStartTime(log.getStartTime());
        vo.setEndTime(log.getEndTime());
        vo.setDurationMs(log.getDurationMs());
        vo.setRowCount(log.getRowCount());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
