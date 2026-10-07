package com.springshop.web.controller;

import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskPageQuery;
import com.springshop.common.excel.task.ExcelTaskService;
import com.springshop.common.excel.task.ExcelTaskStatus;
import com.springshop.common.excel.task.ExcelTaskType;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Excel 异步任务查询与下载接口
 *
 * <p><b>为什么放在 web 模块而不是各业务模块</b>：任务查询与下载对「商品导入」「管理员导出」
 * 等所有业务都是同一套逻辑，且前端需要一个统一的「任务中心」列表页。
 * 放在某个业务模块会让其他模块反向依赖它；web 是唯一依赖全部业务模块的地方。
 *
 * <p><b>权限模型是「归属」而不是权限码</b>：能提交某个导入/导出任务，说明提交时已经过了
 * {@code @PreAuthorize} 的权限码校验；任务本身用 {@code created_by} 做归属校验，
 * 只能查自己的任务。这样不必为「任务中心」再造一套权限码与菜单种子数据，
 * 也不会出现「有导出权限的人能下载别人导出的数据」这种越权。
 */
@Tag(name = "Excel 任务中心", description = "查询异步导入/导出任务的进度并下载结果文件")
@RestController
@RequestMapping("/api/admin/excel-tasks")
@Validated
public class ExcelTaskController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExcelTaskService excelTaskService;

    public ExcelTaskController(ExcelTaskService excelTaskService) {
        this.excelTaskService = excelTaskService;
    }

    @Operation(summary = "分页查询我的导入导出任务", description = "只返回当前登录管理员提交的任务")
    @ApiResponse(responseCode = "200", description = "分页返回当前登录管理员提交的任务")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Result<PageResult<ExcelTaskVO>> page(@ParameterObject @Valid ExcelTaskPageQuery query) {
        return Result.success(excelTaskService.page(query, UserContext.getUserId()));
    }

    @Operation(summary = "查询任务详情与进度", description = "前端轮询该接口刷新进度条")
    @ApiResponse(responseCode = "200", description = "返回任务详情与进度")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{taskNo}")
    public Result<ExcelTaskVO> detail(@Parameter(description = "任务编号") @PathVariable String taskNo) {
        return Result.success(excelTaskService.detail(taskNo, UserContext.getUserId()));
    }

    /**
     * 下载任务结果文件
     *
     * <p>直接写 {@link HttpServletResponse} 而不是返回 {@code ResponseEntity<byte[]>}：
     * 后者会把整个文件读进堆内存，十万行的导出文件轻松上百 MB，
     * 几个并发下载就能把堆打满。这里始终是「流到流」，内存占用与文件大小无关。
     *
     * <p>刻意不用 {@code StreamingResponseBody}（异步写回）：异步派发会再走一遍 Spring Security
     * 过滤链，而 {@code OncePerRequestFilter} 默认跳过 ASYNC 派发、认证上下文不会重建，
     * 结果是文件还没开始写就先被 403 掉。同步流式写在本场景完全够用（内容就在本地磁盘）。
     *
     * <p>返回 {@code void} 是文件下载接口的固有例外：响应体是二进制流，套不进 {@code Result<T>}。
     */
    @Operation(summary = "下载任务结果", description = "导出任务下载结果文件；导入任务下载失败明细")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{taskNo}/download")
    public void download(@Parameter(description = "任务编号") @PathVariable String taskNo,
                         HttpServletResponse response) throws IOException {
        ExcelTask task = excelTaskService.getOwnedTask(taskNo, UserContext.getUserId());
        if (!ExcelTaskStatus.isFinished(task.getStatus())) {
            throw new BusinessException(ResultCode.EXCEL_TASK_NOT_FINISHED);
        }

        String downloadName = resolveDownloadName(task);
        // 刻意不调 setCharacterEncoding：xlsx 是二进制，声明 charset 没有意义，
        // 而且 Tomcat 会把它拼进 Content-Type（application/vnd...sheet;charset=UTF-8），
        // 让「按 Content-Type 精确判断文件类型」的调用方与测试无谓地失配
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                + URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20"));

        try (OutputStream out = response.getOutputStream()) {
            if (isExport(task)) {
                writeExportFile(task, out);
            } else {
                writeErrorDetail(task, out);
            }
            out.flush();
        }
    }

    private boolean isExport(ExcelTask task) {
        return task.getTaskType() != null && task.getTaskType() == ExcelTaskType.EXPORT.getCode();
    }

    /**
     * 导出任务：把结果文件流到响应
     */
    private void writeExportFile(ExcelTask task, OutputStream out) throws IOException {
        String filePath = task.getFilePath();
        if (filePath == null || !Files.isRegularFile(Path.of(filePath))) {
            // 文件已被清理任务删除，或生成过程失败——明确告知而不是返回一个空文件
            throw new BusinessException(ResultCode.EXCEL_TASK_NO_RESULT);
        }
        Files.copy(Path.of(filePath), out);
    }

    /**
     * 导入任务：按需生成失败明细
     */
    private void writeErrorDetail(ExcelTask task, OutputStream out) throws IOException {
        if (task.getFailRows() == null || task.getFailRows() <= 0) {
            throw new BusinessException(ResultCode.EXCEL_TASK_NO_RESULT.getCode(), "本次导入没有失败行，无需下载明细");
        }
        excelTaskService.writeErrorFile(task.getTaskNo(), out);
    }

    private String resolveDownloadName(ExcelTask task) {
        String fileName = task.getFileName();
        if (isExport(task)) {
            return fileName == null || fileName.isBlank() ? task.getTaskNo() + ".xlsx" : fileName;
        }
        String base = fileName == null || fileName.isBlank() ? "导入" : stripExtension(fileName);
        return base + "-失败明细.xlsx";
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
