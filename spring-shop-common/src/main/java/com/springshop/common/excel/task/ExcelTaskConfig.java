package com.springshop.common.excel.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Excel 异步任务线程池
 *
 * <p><b>为什么要独立线程池而不是共用 Spring 默认的 {@code applicationTaskExecutor}</b>：
 * 导入导出是「长耗时 + 吃内存」的任务，一旦和别的异步任务混在一个池里，
 * 几个大文件就能把池子占满，把无关业务一起拖死。独立池可以单独设定并发上限与队列长度。
 *
 * <p><b>为什么拒绝策略是 AbortPolicy 而不是 CallerRunsPolicy</b>：CallerRuns 会让 HTTP 线程
 * 亲自去跑那个几万行的导入，请求必然超时、连接被占死，比直接报错更糟。
 * 这里选择「明确拒绝」，由 {@link ExcelTaskExecutor} 把任务置为失败并给出可读提示。
 */
@Configuration
public class ExcelTaskConfig {

    /**
     * 线程池 bean 名
     *
     * <p>刻意不叫 {@code excelTaskExecutor}：那是 {@link ExcelTaskExecutor} 组件（调度器）
     * 按类名推导出来的默认 bean 名，两者同名会直接抛
     * {@code BeanDefinitionOverrideException}（Spring Boot 默认禁止 bean 覆盖）。
     */
    public static final String EXECUTOR_BEAN_NAME = "excelTaskThreadPool";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor excelTaskThreadPool(ExcelTaskProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix("excel-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅停机：应用重启时等正在跑的导入落完最后一批，避免留下「执行中」的僵尸任务
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
