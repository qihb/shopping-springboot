package com.springshop.common.excel.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ImportError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Excel 任务执行上下文
 *
 * <p>把业务执行体需要的一切收口在一个对象里：源文件、输出文件、查询条件、进度上报、失败明细。
 * 业务代码不用关心「进度多久刷一次库」「失败明细攒多少条落一次」这些与业务无关的细节。
 *
 * <p><b>进度与明细都是缓冲写</b>：一万行导入如果每行都 update 一次 excel_task，
 * 就是一万次 UPDATE，数据库写压力比导入本身还大。这里按
 * {@code excel.task.progress-interval} 攒够一批再刷。
 */
public class ExcelTaskContext {

    private static final Logger log = LoggerFactory.getLogger(ExcelTaskContext.class);

    private final ExcelTask task;

    private final ExcelTaskProperties properties;

    private final ExcelTaskService taskService;

    private final ExcelFileStorage fileStorage;

    private final ObjectMapper objectMapper;

    /** 失败明细缓冲区 */
    private final List<ExcelTaskError> errorBuffer = new ArrayList<>();

    /** 已落库的失败明细条数（超过上限后不再落库，只累加计数） */
    private int persistedErrors;

    private boolean errorOverflowWarned;

    private int processedRows;

    private int successRows;

    private int failRows;

    /** 上一次回写进度时的已处理行数 */
    private int lastFlushedProcessed;

    /**
     * 构造任务上下文
     *
     * <p>正常情况下由 {@link ExcelTaskExecutor} 在后台线程里创建；
     * 声明为 {@code public} 是为了让各业务模块的单元测试能直接构造上下文、
     * 单独驱动「执行体」而不必真的起线程池。
     */
    public ExcelTaskContext(ExcelTask task,
                            ExcelTaskProperties properties,
                            ExcelTaskService taskService,
                            ExcelFileStorage fileStorage,
                            ObjectMapper objectMapper) {
        this.task = task;
        this.properties = properties;
        this.taskService = taskService;
        this.fileStorage = fileStorage;
        this.objectMapper = objectMapper;
    }

    public String getTaskNo() {
        return task.getTaskNo();
    }

    public Long getAdminId() {
        return task.getCreatedBy();
    }

    public String getFileName() {
        return task.getFileName();
    }

    public ExcelTaskProperties getProperties() {
        return properties;
    }

    /**
     * 导入源文件路径
     */
    public Path getSourceFile() {
        return Path.of(task.getFilePath());
    }

    /**
     * 打开导入源文件输入流，由调用方负责关闭
     */
    public InputStream openSource() throws IOException {
        return Files.newInputStream(getSourceFile());
    }

    /**
     * 建导出结果文件并回写任务记录
     *
     * <p>先回写路径再写内容：万一写出过程被强杀，任务记录里也有路径可查，
     * 不会出现「文件在磁盘上但系统不知道它属于哪个任务」的孤儿文件。
     */
    public Path createOutputFile() throws IOException {
        Path path = fileStorage.createExportFile(task.getTaskNo());
        taskService.updateFilePath(task.getTaskNo(), path.toString());
        task.setFilePath(path.toString());
        return path;
    }

    /**
     * 还原导出查询条件
     *
     * <p><b>契约</b>：{@code null} 只表示调用方显式提交了「不筛选」（{@code query == null}），
     * 此时导出全量是预期行为。写入侧 {@code ExcelTaskServiceImpl.writeParams} 已保证
     * 「序列化失败」不会再退化成 null（它会直接拒绝受理任务），所以这里读到 null
     * 就是一个明确的业务语义，而不是「条件丢了」的兜底。
     *
     * <p>反过来，「列里有值但解析不出来」一律抛异常：那说明数据被破坏或版本不兼容，
     * 绝不能当成「没有条件」继续导出全量。
     *
     * @return 未保存条件时返回 null（导出全量）
     */
    public <T> T params(Class<T> queryType) {
        String json = task.getParams();
        if (json == null || json.isBlank()) {
            // 记一条日志：导出全量本身合法，但事后排查「为什么导出了整张表」时，
            // 需要能区分「用户就是要全量」与「条件在某个环节被丢了」。
            log.info("导出任务未保存查询条件，按全量导出（任务号 {}，业务类型 {}）",
                    task.getTaskNo(), task.getBizType());
            return null;
        }
        try {
            return objectMapper.readValue(json, queryType);
        } catch (Exception e) {
            throw new IllegalStateException("导出查询条件解析失败，请重新提交导出任务", e);
        }
    }

    /**
     * 上报进度（绝对计数，调用方维护自己的计数器）
     *
     * <p>取绝对计数而不是增量，是为了避免「某条路径漏加一次」导致进度越漂越远。
     */
    public void reportProgress(int processed, int success, int fail) {
        this.processedRows = processed;
        this.successRows = success;
        this.failRows = fail;
        if (processed - lastFlushedProcessed >= properties.getProgressInterval()) {
            flush();
        }
    }

    /**
     * 记录一条失败明细
     */
    public void addError(int rowNum, String message) {
        if (persistedErrors + errorBuffer.size() >= properties.getMaxErrorRows()) {
            if (!errorOverflowWarned) {
                errorOverflowWarned = true;
                // 只告警一次：一个全错的文件会调用这里上万次，逐条打日志会把日志刷爆
                log.warn("失败明细超过上限 {} 条，后续明细不再落库（任务号 {}）",
                        properties.getMaxErrorRows(), task.getTaskNo());
            }
            return;
        }
        errorBuffer.add(new ExcelTaskError(task.getTaskNo(), rowNum, truncate(message, 480)));
        if (errorBuffer.size() >= properties.getImportBatchSize()) {
            flushErrors();
        }
    }

    /**
     * 批量记录失败明细（如商品导入的「组级失败要铺到组内每一行」）
     */
    public void addErrors(Collection<ImportError> errors) {
        if (errors == null) {
            return;
        }
        for (ImportError error : errors) {
            addError(error.getRowNum(), error.getMessage());
        }
    }

    /**
     * 把进度与失败明细刷到库
     */
    public void flush() {
        flushErrors();
        taskService.updateProgress(task.getTaskNo(), processedRows, successRows, failRows);
        lastFlushedProcessed = processedRows;
    }

    /**
     * 收尾刷库：失败路径上也要尽量把已产生的明细留下，方便用户看到「跑到哪一步断的」
     */
    public void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("任务收尾刷库失败（任务号 {}）", task.getTaskNo(), e);
        }
    }

    public int getProcessedRows() {
        return processedRows;
    }

    public int getSuccessRows() {
        return successRows;
    }

    public int getFailRows() {
        return failRows;
    }

    private void flushErrors() {
        if (errorBuffer.isEmpty()) {
            return;
        }
        List<ExcelTaskError> batch = List.copyOf(errorBuffer);
        errorBuffer.clear();
        taskService.saveErrors(task.getTaskNo(), batch);
        persistedErrors += batch.size();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
