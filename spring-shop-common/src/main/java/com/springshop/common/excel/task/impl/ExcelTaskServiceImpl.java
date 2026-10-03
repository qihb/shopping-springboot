package com.springshop.common.excel.task.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ExcelStreamWriter;
import com.springshop.common.excel.task.ExcelFileStorage;
import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskError;
import com.springshop.common.excel.task.ExcelTaskPageQuery;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.ExcelTaskService;
import com.springshop.common.excel.task.ExcelTaskStatus;
import com.springshop.common.excel.task.ExcelTaskType;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.excel.task.mapper.ExcelTaskErrorMapper;
import com.springshop.common.excel.task.mapper.ExcelTaskMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Excel 任务台账服务实现
 *
 * <p>包名必须与目录一致（{@code ...excel.task.impl}）：包名与路径不一致时，
 * 编译产物会落到 {@code task/} 而不是 {@code task/impl/}，一旦目录里残留旧版本的 class，
 * 就会出现「同一个简单类名的两个不同 FQCN」→ 启动时抛
 * {@code ConflictingBeanDefinitionException}（bean 名 {@code excelTaskServiceImpl} 冲突）。
 */
@Service
public class ExcelTaskServiceImpl implements ExcelTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExcelTaskServiceImpl.class);

    private static final DateTimeFormatter TASK_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 失败明细文件表头 */
    private static final List<String> ERROR_HEADERS = List.of("行号", "失败原因");

    /** 失败明细下载的分页大小：一次读一批、写一批，内存与明细总条数无关 */
    private static final int ERROR_PAGE_SIZE = 2000;

    private final ExcelTaskMapper excelTaskMapper;
    private final ExcelTaskErrorMapper excelTaskErrorMapper;
    private final ExcelFileStorage fileStorage;
    private final ExcelTaskProperties properties;
    private final ObjectMapper objectMapper;

    public ExcelTaskServiceImpl(ExcelTaskMapper excelTaskMapper,
                                ExcelTaskErrorMapper excelTaskErrorMapper,
                                ExcelFileStorage fileStorage,
                                ExcelTaskProperties properties,
                                ObjectMapper objectMapper) {
        this.excelTaskMapper = excelTaskMapper;
        this.excelTaskErrorMapper = excelTaskErrorMapper;
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExcelTask createImportTask(String bizType, String bizName, Long adminId,
                                      String originalFileName, String storedPath) {
        ExcelTask task = newTask(bizType, bizName, adminId, ExcelTaskType.IMPORT, originalFileName);
        task.setFilePath(storedPath);
        excelTaskMapper.insert(task);
        return task;
    }

    @Override
    public ExcelTask createExportTask(String bizType, String bizName, Long adminId,
                                      String fileName, Object query) {
        ExcelTask task = newTask(bizType, bizName, adminId, ExcelTaskType.EXPORT, fileName);
        task.setParams(writeParams(query));
        excelTaskMapper.insert(task);
        return task;
    }

    @Override
    public void assertNotRunning(String bizType, Long adminId) {
        if (excelTaskMapper.countRunningTasks(adminId, bizType) > 0) {
            throw new BusinessException(ResultCode.EXCEL_TASK_DUPLICATE);
        }
    }

    @Override
    public ExcelTask getByTaskNo(String taskNo) {
        ExcelTask task = excelTaskMapper.selectOne(
                Wrappers.<ExcelTask>lambdaQuery().eq(ExcelTask::getTaskNo, taskNo));
        if (task == null) {
            throw new BusinessException(ResultCode.EXCEL_TASK_NOT_FOUND);
        }
        return task;
    }

    @Override
    public ExcelTask getOwnedTask(String taskNo, Long adminId) {
        ExcelTask task = getByTaskNo(taskNo);
        if (adminId == null || !adminId.equals(task.getCreatedBy())) {
            // 刻意不返回「无权访问」：那等于告诉对方这个任务号是存在的
            throw new BusinessException(ResultCode.EXCEL_TASK_NOT_FOUND);
        }
        return task;
    }

    @Override
    public PageResult<ExcelTaskVO> page(ExcelTaskPageQuery query, Long adminId) {
        Page<ExcelTask> page = query.toPage();
        IPage<ExcelTask> result = excelTaskMapper.selectPage(page,
                Wrappers.<ExcelTask>lambdaQuery()
                        .eq(ExcelTask::getCreatedBy, adminId)
                        .eq(query.getTaskType() != null, ExcelTask::getTaskType, query.getTaskType())
                        .eq(query.getBizType() != null && !query.getBizType().isBlank(),
                                ExcelTask::getBizType, query.getBizType())
                        .eq(query.getStatus() != null, ExcelTask::getStatus, query.getStatus())
                        .orderByDesc(ExcelTask::getId));

        List<ExcelTaskVO> records = new ArrayList<>(result.getRecords().size());
        for (ExcelTask task : result.getRecords()) {
            records.add(toVO(task));
        }
        PageResult<ExcelTaskVO> pageResult = new PageResult<>();
        pageResult.setRecords(records);
        pageResult.setTotal(result.getTotal());
        pageResult.setPages(result.getPages());
        pageResult.setCurrent(result.getCurrent());
        pageResult.setSize(result.getSize());
        return pageResult;
    }

    @Override
    public ExcelTaskVO detail(String taskNo, Long adminId) {
        return toVO(getOwnedTask(taskNo, adminId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markRunning(String taskNo) {
        ExcelTask update = new ExcelTask();
        update.setStatus(ExcelTaskStatus.RUNNING.getCode());
        update.setStartTime(LocalDateTime.now());
        excelTaskMapper.update(update, Wrappers.<ExcelTask>lambdaUpdate().eq(ExcelTask::getTaskNo, taskNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(String taskNo, int processedRows, int successRows, int failRows) {
        ExcelTask update = new ExcelTask();
        update.setProcessedRows(processedRows);
        update.setSuccessRows(successRows);
        update.setFailRows(failRows);
        excelTaskMapper.update(update, Wrappers.<ExcelTask>lambdaUpdate().eq(ExcelTask::getTaskNo, taskNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(String taskNo, int processedRows, int successRows, int failRows) {
        ExcelTask update = new ExcelTask();
        update.setStatus(ExcelTaskStatus.SUCCESS.getCode());
        update.setProcessedRows(processedRows);
        update.setSuccessRows(successRows);
        update.setFailRows(failRows);
        update.setTotalRows(processedRows);
        update.setEndTime(LocalDateTime.now());
        update.setErrorMsg(null);
        excelTaskMapper.update(update, Wrappers.<ExcelTask>lambdaUpdate().eq(ExcelTask::getTaskNo, taskNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String taskNo, String errorMsg) {
        ExcelTask update = new ExcelTask();
        update.setStatus(ExcelTaskStatus.FAILED.getCode());
        update.setErrorMsg(truncate(errorMsg, 900));
        update.setEndTime(LocalDateTime.now());
        excelTaskMapper.update(update, Wrappers.<ExcelTask>lambdaUpdate().eq(ExcelTask::getTaskNo, taskNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void updateFilePath(String taskNo, String filePath) {
        ExcelTask update = new ExcelTask();
        update.setFilePath(filePath);
        excelTaskMapper.update(update, Wrappers.<ExcelTask>lambdaUpdate().eq(ExcelTask::getTaskNo, taskNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void saveErrors(String taskNo, List<ExcelTaskError> errors) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        excelTaskErrorMapper.insertBatch(errors);
    }

    @Override
    public long countErrors(String taskNo) {
        return excelTaskErrorMapper.countByTaskNo(taskNo);
    }

    @Override
    public void writeErrorFile(String taskNo, OutputStream out) throws IOException {
        try (ExcelStreamWriter writer = ExcelStreamWriter.ofHeaders(out, ERROR_HEADERS, "失败明细")) {
            int offset = 0;
            while (true) {
                List<ExcelTaskError> batch = excelTaskErrorMapper.selectPageByTaskNo(taskNo, offset, ERROR_PAGE_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                List<List<String>> rows = new ArrayList<>(batch.size());
                for (ExcelTaskError error : batch) {
                    rows.add(List.of(String.valueOf(error.getRowNum()), error.getMessage()));
                }
                writer.write(rows);
                offset += batch.size();
            }
        }
    }

    @Override
    public int cleanupExpiredFiles() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(properties.getFileRetainHours());
        List<ExcelTask> expired = excelTaskMapper.selectExpiredFiles(deadline);
        for (ExcelTask task : expired) {
            fileStorage.deleteQuietly(task.getFilePath());
            excelTaskMapper.clearFilePath(task.getTaskNo());
        }
        return fileStorage.cleanupExpired();
    }

    @Override
    public int failStaleTasks() {
        // 超过保留期仍未结束，基本可以确定是进程被杀或容器被驱逐
        LocalDateTime deadline = LocalDateTime.now().minusHours(properties.getFileRetainHours());
        int affected = excelTaskMapper.failStaleTasks(deadline,
                "任务执行超时或应用重启导致中断，请重新提交");
        if (affected > 0) {
            log.warn("清理僵尸 Excel 任务 {} 条", affected);
        }
        return affected;
    }

    private ExcelTask newTask(String bizType, String bizName, Long adminId,
                              ExcelTaskType taskType, String fileName) {
        ExcelTask task = new ExcelTask();
        task.setTaskNo(generateTaskNo(taskType));
        task.setBizType(bizType);
        task.setBizName(bizName);
        task.setTaskType(taskType.getCode());
        task.setStatus(ExcelTaskStatus.PENDING.getCode());
        task.setFileName(fileName);
        task.setTotalRows(0);
        task.setProcessedRows(0);
        task.setSuccessRows(0);
        task.setFailRows(0);
        task.setCreatedBy(adminId);
        return task;
    }

    /**
     * 任务号 = 方向前缀 + 时间戳 + 随机后缀
     *
     * <p>带时间戳是为了排查时一眼看出任务提交时间；带随机后缀是因为同一秒内可能提交多个任务，
     * 而 {@code uk_excel_task_no} 是唯一索引，纯时间戳会撞键。
     */
    private String generateTaskNo(ExcelTaskType taskType) {
        String prefix = taskType == ExcelTaskType.IMPORT ? "I" : "E";
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + LocalDateTime.now().format(TASK_NO_TIME) + random;
    }

    private String writeParams(Object query) {
        if (query == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(query);
        } catch (Exception e) {
            log.warn("序列化导出查询条件失败，导出将不带筛选条件", e);
            return null;
        }
    }

    @Override
    public ExcelTaskVO toVO(ExcelTask task) {        ExcelTaskVO vo = new ExcelTaskVO();
        vo.setTaskNo(task.getTaskNo());
        vo.setBizType(task.getBizType());
        vo.setBizName(task.getBizName());
        vo.setTaskType(task.getTaskType());
        vo.setTaskTypeName(ExcelTaskType.IMPORT.getCode() == task.getTaskType()
                ? ExcelTaskType.IMPORT.getLabel() : ExcelTaskType.EXPORT.getLabel());
        vo.setStatus(task.getStatus());
        vo.setStatusName(ExcelTaskStatus.of(task.getStatus()).getLabel());
        vo.setFileName(task.getFileName());
        vo.setTotalRows(task.getTotalRows());
        vo.setProcessedRows(task.getProcessedRows());
        vo.setSuccessRows(task.getSuccessRows());
        vo.setFailRows(task.getFailRows());
        vo.setProgress(resolveProgress(task));
        vo.setDownloadable(isDownloadable(task));
        vo.setErrorMsg(task.getErrorMsg());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setCreateTime(task.getCreateTime());
        return vo;
    }

    private int resolveProgress(ExcelTask task) {
        if (ExcelTaskStatus.isFinished(task.getStatus())) {
            return 100;
        }
        int total = task.getTotalRows() == null ? 0 : task.getTotalRows();
        int processed = task.getProcessedRows() == null ? 0 : task.getProcessedRows();
        if (total <= 0) {
            // 导出任务在开始前不知道总量，进度只能靠状态表达
            return processed > 0 ? 1 : 0;
        }
        return Math.min(99, processed * 100 / total);
    }

    private boolean isDownloadable(ExcelTask task) {
        int taskType = task.getTaskType() == null ? 0 : task.getTaskType();
        if (taskType == ExcelTaskType.EXPORT.getCode()) {
            return task.getStatus() != null
                    && task.getStatus() == ExcelTaskStatus.SUCCESS.getCode()
                    && fileStorage.exists(task.getFilePath());
        }
        // 导入任务可下载的是「失败明细」，没有失败行就没什么可下的
        int failRows = task.getFailRows() == null ? 0 : task.getFailRows();
        return ExcelTaskStatus.isFinished(task.getStatus()) && failRows > 0;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
