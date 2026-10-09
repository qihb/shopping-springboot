package com.springshop.stats.task;

import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.stats.config.RecallProperties;
import com.springshop.stats.entity.StatsTaskLog;
import com.springshop.stats.mapper.StatsTaskLogMapper;
import com.springshop.stats.service.CartRecallService;
import com.springshop.stats.vo.RecallBuildResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.UUID;

/**
 * 加购未买召回圈人定时任务
 *
 * <p><b>时区</b>：{@code @Scheduled} 的 cron 使用 JVM 默认时区，与
 * {@code spring.jackson.time-zone} 无关。容器镜像默认时区通常是 UTC，
 * 不显式指定 {@code zone} 的话「凌晨 4 点」会变成北京时间中午 12 点，
 * 而本地 macOS 是 CST，这个 bug 在开发环境永远复现不出来。
 *
 * <p><b>多实例</b>：{@code @EnableScheduling} 在每个实例各自生效，集群部署会同时跑 N 次。
 * 用 Redis 锁保证同一时刻只有一个实例执行；Redis 故障时降级为「都执行」，
 * 由 {@link CartRecallService#build} 自身的幂等（先删待处理再重建）兜底。
 *
 * <p><b>异常</b>：{@code @Scheduled} 方法抛出的异常不会杀掉调度线程（下次 cron 照常触发），
 * 但会被框架吞掉，容易出现「今天没出数据但没人发现」。因此这里统一 try/catch，
 * 并落 {@code stats_task_log} 作为排查入口。
 *
 * <p><b>为什么用 {@code enabled} 开关而不是 {@code @ConditionalOnProperty}}：后者会让 Bean
 * 在测试 profile 下不存在，导致依赖它的后台 Controller 无法注入、上下文启动失败。
 * 改为在调度方法内做守卫，Bean 始终存在，测试与手动补数都不受影响。
 */
@Component
public class CartRecallTask {

    private static final Logger log = LoggerFactory.getLogger(CartRecallTask.class);

    /**
     * 任务名，同时是 {@code stats_task_log.task_name} 的写入值
     *
     * <p>公开为常量是为了让查询侧（{@code TaskLogQueryServiceImpl} 的默认过滤条件）直接引用它，
     * 而不是各自再写一遍字面量 —— 两处字符串一旦不同步，默认查询会静默查不到任何记录。
     */
    public static final String TASK_NAME = "cart-recall";

    /** 锁 TTL：跑批远快于此，仅作为实例崩溃后的自动解锁兜底 */
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    /** 释放锁必须校验持有者，否则可能误删其他实例刚抢到的锁 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final CartRecallService cartRecallService;
    private final StatsTaskLogMapper taskLogMapper;
    private final RecallProperties props;
    private final StringRedisTemplate stringRedisTemplate;

    public CartRecallTask(CartRecallService cartRecallService,
                          StatsTaskLogMapper taskLogMapper,
                          RecallProperties props,
                          StringRedisTemplate stringRedisTemplate) {
        this.cartRecallService = cartRecallService;
        this.taskLogMapper = taskLogMapper;
        this.props = props;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 每天凌晨 4 点（配置时区）执行圈人
     *
     * <p>这里只做调度、加锁与异常兜底，真正的圈人逻辑在 Service 里，
     * 以便单元测试无需启动 Spring 容器即可覆盖业务规则。
     */
    @Scheduled(cron = "${stats.cart-recall.cron:0 0 4 * * ?}",
               zone = "${stats.cart-recall.zone:Asia/Shanghai}")
    public void scheduledBuild() {
        if (!props.isEnabled()) {
            // 测试环境关闭：@EnableScheduling 是全局的，@SpringBootTest 会真实注册所有 @Scheduled
            log.debug("召回圈人定时任务已关闭（stats.cart-recall.enabled=false），跳过本次触发");
            return;
        }
        try {
            run(LocalDate.now(resolveZone()));
        } catch (Exception e) {
            // 定时触发路径不向上抛：抛了也只会被框架吞掉，这里已落库记录
            log.error("召回圈人定时任务异常", e);
        }
    }

    /**
     * 执行一次圈人（定时触发与后台手动补数共用）
     *
     * @throws BusinessException 未抢到锁（已有实例在执行）或日期非法
     */
    public RecallBuildResultVO run(LocalDate statDate) {
        String lockToken = tryLock();
        if (lockToken == null) {
            throw new BusinessException(ResultCode.STATS_RECALL_RUNNING);
        }

        LocalDateTime startTime = LocalDateTime.now(resolveZone());
        StatsTaskLog taskLog = new StatsTaskLog();
        taskLog.setTaskName(TASK_NAME);
        taskLog.setStatDate(statDate);
        taskLog.setStartTime(startTime);

        try {
            RecallBuildResultVO result = cartRecallService.build(statDate);
            taskLog.setStatus(1);
            taskLog.setRowCount(result.getTargetCount());
            return result;
        } catch (RuntimeException e) {
            taskLog.setStatus(0);
            taskLog.setErrorMsg(truncate(e.getMessage()));
            log.error("召回圈人执行失败 statDate={}", statDate, e);
            throw e;
        } finally {
            LocalDateTime endTime = LocalDateTime.now(resolveZone());
            taskLog.setEndTime(endTime);
            taskLog.setDurationMs(Duration.between(startTime, endTime).toMillis());
            saveTaskLog(taskLog);
            releaseLock(lockToken);
        }
    }

    /**
     * 抢占分布式锁
     *
     * @return 持有者令牌；返回 null 表示已被其他实例占用。
     *         Redis 异常时返回令牌并降级为「不加锁执行」——与项目既有
     *         「Redis 仅做加速、故障静默降级」的策略一致，靠任务幂等兜底。
     */
    private String tryLock() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(RedisKeys.statsRecallLock(), token, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return token;
            }
            if (acquired == null) {
                log.warn("召回圈人获取分布式锁返回空，降级为不加锁执行");
                return token;
            }
            log.info("召回圈人已有实例在执行，本次跳过");
            return null;
        } catch (Exception e) {
            log.warn("召回圈人获取分布式锁异常，降级为不加锁执行", e);
            return token;
        }
    }

    /**
     * 释放锁：Lua 原子校验持有者，避免误删其他实例的锁；失败仅告警，等 TTL 自然过期
     */
    private void releaseLock(String token) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT,
                    Collections.singletonList(RedisKeys.statsRecallLock()), token);
        } catch (Exception e) {
            log.warn("召回圈人释放分布式锁失败，等待 TTL 自动过期", e);
        }
    }

    /**
     * 落任务日志：独立于圈人事务，即使圈人回滚也能留下失败痕迹
     */
    private void saveTaskLog(StatsTaskLog taskLog) {
        try {
            taskLogMapper.insert(taskLog);
        } catch (Exception e) {
            log.error("写统计任务日志失败 taskName={} statDate={}", TASK_NAME, taskLog.getStatDate(), e);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    private ZoneId resolveZone() {
        try {
            return ZoneId.of(props.getZone());
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
