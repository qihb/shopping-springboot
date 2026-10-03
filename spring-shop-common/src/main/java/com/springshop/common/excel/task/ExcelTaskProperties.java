package com.springshop.common.excel.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Excel 异步任务配置（前缀 {@code excel.task}）
 *
 * <p>所有阈值都配置化，理由与 stats 模块一致：批大小、线程数这类参数和部署机器强相关，
 * 本地 4 核与生产 16 核的最优值差一个量级，硬编码在代码里就只能改代码重发。
 */
@Component
@ConfigurationProperties(prefix = "excel.task")
public class ExcelTaskProperties {

    /** 总开关：关掉后提交任务会直接报错（测试环境可用它避免异步线程干扰断言） */
    private boolean enabled = true;

    /** 临时文件目录：导入源文件与导出结果文件都落在这里 */
    private String tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "spring-shop", "excel").toString();

    /** 异步线程池核心线程数 */
    private int corePoolSize = 2;

    /** 异步线程池最大线程数 */
    private int maxPoolSize = 4;

    /** 异步队列容量：满了直接拒绝并给出可读提示，不阻塞 HTTP 线程 */
    private int queueCapacity = 50;

    /** 空闲线程回收时间（秒） */
    private int keepAliveSeconds = 120;

    /** 导入落库批大小：一批多少行写一次库 */
    private int importBatchSize = 500;

    /** 导出分页大小：一页多少行写一次 Excel 并落一批数据 */
    private int exportPageSize = 5000;

    /** 单次导入的数据行上限 */
    private int maxImportRows = 100_000;

    /** 失败明细最多保留条数：防止一个全错的文件把明细表写爆 */
    private int maxErrorRows = 10_000;

    /** 每处理多少行回写一次进度（回写太频繁会给数据库带来无谓的写压力） */
    private int progressInterval = 500;

    /** 临时文件保留小时数，超期由清理任务删除 */
    private int fileRetainHours = 24;

    /** 临时文件清理任务的 cron（默认每天 03:30） */
    private String cleanupCron = "0 30 3 * * ?";

    /** 清理任务时区：cron 用的是 JVM 默认时区，容器通常是 UTC，必须显式指定 */
    private String zone = "Asia/Shanghai";

    public Path tmpDirPath() {
        return Paths.get(tmpDir);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTmpDir() { return tmpDir; }
    public void setTmpDir(String tmpDir) { this.tmpDir = tmpDir; }
    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
    public int getImportBatchSize() { return importBatchSize; }
    public void setImportBatchSize(int importBatchSize) { this.importBatchSize = importBatchSize; }
    public int getExportPageSize() { return exportPageSize; }
    public void setExportPageSize(int exportPageSize) { this.exportPageSize = exportPageSize; }
    public int getMaxImportRows() { return maxImportRows; }
    public void setMaxImportRows(int maxImportRows) { this.maxImportRows = maxImportRows; }
    public int getMaxErrorRows() { return maxErrorRows; }
    public void setMaxErrorRows(int maxErrorRows) { this.maxErrorRows = maxErrorRows; }
    public int getProgressInterval() { return progressInterval; }
    public void setProgressInterval(int progressInterval) { this.progressInterval = progressInterval; }
    public int getFileRetainHours() { return fileRetainHours; }
    public void setFileRetainHours(int fileRetainHours) { this.fileRetainHours = fileRetainHours; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
}
