package com.springshop.common.excel.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * Excel 任务调度器
 *
 * <p>职责边界：<b>受理 + 调度 + 状态兜底</b>。
 * <ul>
 *   <li>受理：导入先落盘再建任务行（顺序不能反——先建行后落盘，落盘失败会留下一个永远跑不动的任务）；</li>
 *   <li>调度：丢进独立的 excel-task 线程池，HTTP 线程立刻返回 taskNo；</li>
 *   <li>状态兜底：无论业务执行体抛什么异常，都把任务写成终态，
 *       绝不允许任务永远停在「执行中」——那对用户就是「一直转圈」。</li>
 * </ul>
 */
@Component
public class ExcelTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExcelTaskExecutor.class);

    private final ThreadPoolTaskExecutor executor;

    private final ExcelTaskService taskService;

    private final ExcelFileStorage fileStorage;

    private final ExcelTaskProperties properties;

    private final ObjectMapper objectMapper;

    public ExcelTaskExecutor(@Qualifier(ExcelTaskConfig.EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor executor,
                             ExcelTaskService taskService,
                             ExcelFileStorage fileStorage,
                             ExcelTaskProperties properties,
                             ObjectMapper objectMapper) {
        this.executor = executor;
        this.taskService = taskService;
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理导入任务并异步执行
     *
     * @param bizType  业务类型编码
     * @param bizName  业务类型展示名
     * @param adminId  提交人（管理员 id），从登录态取
     * @param file     上传的文件
     * @param worker   业务执行体
     * @return 已受理的任务（含 taskNo），前端据此轮询进度
     */
    public ExcelTaskVO submitImport(String bizType, String bizName, Long adminId,
                                    MultipartFile file, ExcelTaskWorker worker) {
        assertEnabled();
        taskService.assertNotRunning(bizType, adminId);

        String taskNo = null;
        try {
            // 任务号要先于任务行确定：文件名依赖它，落盘失败时也便于在日志里定位
            ExcelTask draft = taskService.createImportTask(bizType, bizName, adminId,
                    file.getOriginalFilename(), null);
            taskNo = draft.getTaskNo();
            Path stored = fileStorage.saveImportFile(taskNo, file.getOriginalFilename(), file);
            taskService.updateFilePath(taskNo, stored.toString());
            draft.setFilePath(stored.toString());
            dispatch(draft, worker);
            return taskService.toVO(draft);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("受理导入任务失败 bizType={} adminId={} taskNo={}", bizType, adminId, taskNo, e);
            if (taskNo != null) {
                taskService.markFailed(taskNo, "文件保存失败，请重新提交");
            }
            throw new BusinessException(ResultCode.EXCEL_TASK_BUSY.getCode(), "文件保存失败，请重新提交");
        }
    }

    /**
     * 受理导出任务并异步执行
     *
     * @param fileName 导出文件名（展示用，如「商品列表.xlsx」）
     * @param query    导出查询条件，序列化后存库供后台线程还原
     */
    public ExcelTaskVO submitExport(String bizType, String bizName, Long adminId,
                                    String fileName, Object query, ExcelTaskWorker worker) {
        assertEnabled();
        taskService.assertNotRunning(bizType, adminId);
        ExcelTask task = taskService.createExportTask(bizType, bizName, adminId, fileName, query);
        dispatch(task, worker);
        return taskService.toVO(task);
    }

    /**
     * 丢进线程池；队列满时把任务写成失败并抛出可读的业务异常
     *
     * <p>不阻塞等待、也不降级为同步执行：让 HTTP 线程去跑几万行的导入，
     * 请求必然超时且连接被占死，比直接告诉用户「排队满了」更糟。
     */
    private void dispatch(ExcelTask task, ExcelTaskWorker worker) {
        try {
            executor.execute(() -> run(task, worker));
        } catch (TaskRejectedException e) {
            log.warn("Excel 任务排队已满，拒绝受理 taskNo={} bizType={}", task.getTaskNo(), task.getBizType());
            taskService.markFailed(task.getTaskNo(), "任务排队已满，请稍后重新提交");
            throw new BusinessException(ResultCode.EXCEL_TASK_BUSY);
        }
    }

    /**
     * 后台线程执行体：状态流转与异常兜底都在这里，业务执行体只关心业务
     */
    private void run(ExcelTask task, ExcelTaskWorker worker) {
        String taskNo = task.getTaskNo();
        ExcelTaskContext context = new ExcelTaskContext(task, properties, taskService, fileStorage, objectMapper);
        long startAt = System.currentTimeMillis();
        try {
            // CAS 领取任务。抢不到说明任务已被别人处理过（典型场景：清理任务已把它判为
            // 失败），这时继续跑只会产出一份没人认领的结果，所以直接放弃
            if (taskService.markRunning(taskNo) == 0) {
                log.warn("Excel 任务未能进入「执行中」（状态已被变更），放弃执行 taskNo={} bizType={}",
                        taskNo, task.getBizType());
                return;
            }
            worker.run(context);
            context.flush();
            if (taskService.markSuccess(taskNo, context.getProcessedRows(),
                    context.getSuccessRows(), context.getFailRows()) == 0) {
                // 业务数据已经落库，但任务记录已是终态（多半是跑超 24 小时被清理任务判失败）。
                // 不能强行改回成功，只能留一条日志让人工知道「这批数据其实进来了」
                log.warn("Excel 任务已完成但终态写入被拒绝（任务已被判为终态），"
                                + "业务数据可能已落库，请人工核对 taskNo={} bizType={} 处理 {} 行",
                        taskNo, task.getBizType(), context.getProcessedRows());
                return;
            }
            log.info("Excel 任务完成 taskNo={} bizType={} 处理 {} 行 / 成功 {} / 失败 {}，耗时 {} ms",
                    taskNo, task.getBizType(), context.getProcessedRows(), context.getSuccessRows(),
                    context.getFailRows(), System.currentTimeMillis() - startAt);
        } catch (Exception e) {
            log.error("Excel 任务执行失败 taskNo={} bizType={}", taskNo, task.getBizType(), e);
            context.flushQuietly();
            if (taskService.markFailed(taskNo, readableMessage(e)) == 0) {
                log.warn("Excel 任务失败终态写入被拒绝（任务已是终态，保留原有失败原因）taskNo={}", taskNo);
            }
        }
    }

    /**
     * 把异常翻成人话写进 error_msg
     *
     * <p>业务异常与「文件解析失败」本来就是我们自己写的可读文案，直接用；
     * 其余异常（NPE、SQL 异常）只给出简短类型，完整堆栈留在日志里，
     * 不要把 SQL 语句之类的内部信息暴露到接口上。
     */
    private String readableMessage(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException.getMessage();
        }
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message.length() > 300 ? message.substring(0, 300) : message;
        }
        return "任务执行失败（" + e.getClass().getSimpleName() + "），请查看服务端日志";
    }

    private void assertEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.EXCEL_TASK_DISABLED);
        }
    }
}
