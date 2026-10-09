package com.springshop.stats.service;

import com.springshop.common.result.PageResult;
import com.springshop.stats.dto.StatsTaskLogPageQuery;
import com.springshop.stats.vo.StatsTaskLogVO;

/**
 * 统计任务执行日志查询服务
 *
 * <p>定时任务把每次执行结果写进 {@code stats_task_log}，但此前**只有写没有读** ——
 * 运营无法回答「今天圈人任务跑没跑、产出多少行、有没有失败」。
 * 这个服务就是那个缺失的读路径，只读、不产生任何写副作用。
 */
public interface TaskLogQueryService {

    /**
     * 分页查询任务执行日志
     *
     * @param query 过滤条件；{@code taskName} 为空时按默认任务（cart-recall）过滤
     */
    PageResult<StatsTaskLogVO> page(StatsTaskLogPageQuery query);
}
