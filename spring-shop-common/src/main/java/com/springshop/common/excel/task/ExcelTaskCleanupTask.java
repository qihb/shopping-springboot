package com.springshop.common.excel.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Excel 任务清理
 *
 * <p>做两件事，都是「不做会慢慢烂掉」的兜底：
 * <ol>
 *   <li><b>清磁盘</b>：导入源文件与导出结果文件都是临时文件，不清理的话
 *       每天几十个几十 MB 的文件会把磁盘吃满；</li>
 *   <li><b>清僵尸任务</b>：应用被 kill -9 / 容器被驱逐时来不及写终态，
 *       任务会永远停在「执行中」，前端一直转圈。按创建时间兜底判失败。</li>
 * </ol>
 *
 * <p><b>多实例</b>：与 stats 的跑批任务不同，这里不加分布式锁——两个实例同时清理
 * 只是重复删同一批文件，是幂等的；为了省这点重复去引入 Redis 锁，
 * 反而会让 Redis 故障时清理彻底停摆。
 */
@Component
public class ExcelTaskCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ExcelTaskCleanupTask.class);

    private final ExcelTaskService taskService;

    public ExcelTaskCleanupTask(ExcelTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(cron = "${excel.task.cleanup-cron:0 30 3 * * ?}",
               zone = "${excel.task.zone:Asia/Shanghai}")
    public void cleanup() {
        try {
            int staleTasks = taskService.failStaleTasks();
            int deletedFiles = taskService.cleanupExpiredFiles();
            log.info("Excel 任务清理完成：僵尸任务 {} 条，临时文件 {} 个", staleTasks, deletedFiles);
        } catch (Exception e) {
            // 定时任务抛异常会被框架吞掉且不再重试，这里自己兜住并留下日志
            log.error("Excel 任务清理失败", e);
        }
    }
}
