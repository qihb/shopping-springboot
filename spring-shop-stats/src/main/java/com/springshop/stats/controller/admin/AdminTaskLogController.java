package com.springshop.stats.controller.admin;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.stats.dto.StatsTaskLogPageQuery;
import com.springshop.stats.service.TaskLogQueryService;
import com.springshop.stats.vo.StatsTaskLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务执行日志查询接口（后台）
 *
 * <p><b>为什么单独建一个 Controller，不塞进 {@code AdminRecallController}</b>：后者的
 * {@code @RequestMapping} 是 {@code /api/admin/stats/recall}，而任务日志是「数据运营」下
 * 与召回<b>并列</b>的能力，不属于 recall 子域。塞进去 URL 会变成
 * {@code /recall/task-logs}，语义错了；将来 {@code OrderTimeoutTask} /
 * {@code ExcelTaskCleanupTask} 也落这张表时，这个接口要能一起服务它们。
 *
 * <p>走管理后台过滤链（{@code /api/admin/**}），无需改 {@code SecurityConfig}；
 * 权限由 {@code @PreAuthorize} 的权限码控制，需在「菜单管理」中配置对应权限。
 *
 * <p>只读接口，不加 {@code @OperationLog}：查日志本身不需要再产生一条日志。
 */
@Tag(name = "数据运营（后台）", description = "定时任务执行日志查询")
@RestController
@RequestMapping("/api/admin/stats/task-logs")
@Validated
public class AdminTaskLogController {

    private final TaskLogQueryService taskLogQueryService;

    public AdminTaskLogController(TaskLogQueryService taskLogQueryService) {
        this.taskLogQueryService = taskLogQueryService;
    }

    @Operation(summary = "分页查询定时任务执行日志",
            description = "回答「今天任务跑没跑、产出多少行、有没有失败」。"
                    + "不传 taskName 时只查默认任务（cart-recall）；日期按执行时间过滤，结束日期含当天。")
    @ApiResponse(responseCode = "200", description = "分页返回任务执行日志")
    @PreAuthorize("hasAuthority('stats:task-log:list')")
    @GetMapping
    public Result<PageResult<StatsTaskLogVO>> page(@ParameterObject @Valid StatsTaskLogPageQuery query) {
        return Result.success(taskLogQueryService.page(query));
    }
}
