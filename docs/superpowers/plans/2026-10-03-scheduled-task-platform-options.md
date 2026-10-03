# 定时任务平台化 + 重跑机制 — 技术方案（选项对比，待拍板）

> **状态**：方案评审中，**未落地任何代码**。
> **背景**：目前 2 个定时任务各自为政，后续任务会持续增加，且缺统一的重跑机制。
> **前置文档**：`2026-10-03-cart-recall-phase1-results.md`（已上线的圈人任务）。

---

## 1. 结论先行

### 1.1 现状能撑 2 个任务，撑不到 10 个

问题不在「调度」本身 —— `@Scheduled` 很好用。问题在**每个任务都要自己实现一遍基础设施**，而且实现得还不一样。

现有两个任务的真实对比：

| 能力 | `OrderTimeoutTask` | `CartRecallTask` |
|------|-------------------|------------------|
| 代码行数 | 62 | 197 |
| 分布式锁 | ❌ **没有**（多实例会重复跑） | ✅ Redis 锁 |
| 任务执行日志 | ❌ 只有 `log.error` | ✅ `stats_task_log` 表 |
| 开关（测试可关） | ❌ 没有 | ✅ `enabled` |
| 手动触发 | ❌ 没有 | ✅ `POST /build` |
| 时区显式声明 | N/A（`fixedDelay` 不涉及） | ✅ `zone` |
| 幂等 | 靠条件更新 | 先删后插 |

**同一个项目里，两个任务的能力集完全不同。** 这不是谁写错了 —— 是因为基础设施没有抽象层，每个任务都是「需要什么就自己补什么」。任务一多，必然出现：

- 有的任务忘了加锁 → 扩容后重复执行
- 有的任务没落日志 → 出问题查不到
- 有的任务没开关 → 测试环境乱跑
- 没人能回答「现在系统里到底有多少个定时任务、都在什么时候跑」

### 1.2 三个真问题

| # | 问题 | 具体表现 |
|---|------|---------|
| 1 | **样板代码复制** | 每新增一个任务，约 **150 行**是锁/日志/异常/开关/手动触发的重复代码，只有 ~20 行是业务逻辑（`CartRecallTask` 197 行里，真正调用业务只有 `cartRecallService.build(statDate)` 这一行） |
| 2 | **没有重跑机制** | 失败只能等第二天 cron，或者调那个任务**专属**的手动接口（每个任务的接口路径、参数都不一样） |
| 3 | **没有告警** | 失败只有一行 `log.error`。没人看日志 = 静默失败 |

### 1.3 一个关键认知：**不是所有任务都能重跑**

这是本方案最重要的前提。「重跑」听起来只是「再执行一次」，但对**有副作用的任务**（发券、发短信、扣库存），盲目重跑 = 重复发券 / 重复扣库存 / 资损。

所以本方案的核心不是「怎么再跑一次」，而是**先给任务分类，再决定谁能重跑、怎么跑才安全**。详见第 3 节。

### 1.4 推荐路线

| 阶段 | 做什么 | 触发条件 |
|------|--------|---------|
| **现在** | 自研**轻量**任务层（统一锁 + 统一日志 + 统一重跑 + 统一告警），把现有 2 个任务迁过去 | 立即 |
| **未来** | 任务数 > 15、需要可视化编排或分片时，迁 XXL-JOB | 见 4.3 的触发条件 |

**为什么现在不上 XXL-JOB**：单体应用 + 任务数在 10 上下，为它独立部署一套调度中心（含自己的库和运维）不划算。而且抽象层做好之后，未来迁移只需要换「调度驱动」，任务代码不用动。

---

## 2. 现状盘点：改动会波及哪些地方

| 模块 | 改动 | 风险 |
|------|------|------|
| `spring-shop-common`（或新建 `spring-shop-task`） | 任务抽象层：模板方法 + 注解 + 注册表 + 锁管理器 | 中，全新能力 |
| `spring-shop-stats` | `CartRecallTask` 改为继承模板 | 低，行为等价 |
| `spring-shop-order` | `OrderTimeoutTask` 改为继承模板（**顺带补上缺失的锁**） | **中**：从无锁变有锁，需验证不影响超时取消时效 |
| 新建迁移 `V8__task_platform.sql` | `task_exec_log` 表 + `task_rerun_log` 表 | 低 |
| `AdminDataInitializer` | 新增「系统管理 → 定时任务」菜单 | 低 |
| 告警 | `TaskAlertNotifier` + 企微/钉钉 webhook | 低，但需外部 webhook 地址 |
| `SecurityConfig` | 新接口走 `/api/admin/**` 链，**无需改白名单** | 低 |

⚠️ **`OrderTimeoutTask` 补锁要谨慎**：它每 60 秒跑一次、处理量小、且 `systemCancel` 是条件更新（重复跑安全）。加锁反而可能因为锁竞争/Redis 抖动影响取消时效。**建议它保持不加锁**，只在抽象层声明 `lockStrategy = NONE`。这正说明抽象层需要支持「按任务选择策略」，而不是一刀切。

---

## 3. 关键设计：任务分类与幂等策略

### 3.1 按副作用分类

| 类别 | 定义 | 能否重跑 | 例子 |
|------|------|---------|------|
| **A. 全量重建型** | 每次执行覆盖式产出，无外部副作用 | ✅ 安全 | 圈人人群池、选品、日报统计 |
| **B. 按天快照型** | 产出 (任务, 业务日期) 唯一的一份结果 | ✅ 安全（覆盖） | 每日 GMV 报表、库存快照 |
| **C. 状态机推进型** | 只处理「处于特定状态」的记录 | ✅ 安全（天然幂等） | 订单超时取消、支付超时关闭 |
| **D. 副作用型** | 有外部不可回滚的动作 | ❌ **默认禁止** | 发券、发短信、调第三方 |

**D 类怎么办？** 两个办法，二选一：

1. **声明不可重跑**：`@ShopTask(rerunnable = false)`，重跑接口直接拒绝并返回明确原因。简单、安全，推荐起步用这个。
2. **引入幂等键**：给每次「动作」分配业务唯一键，例如发券用 `(task_name, biz_date, user_id, coupon_template_id)` 做唯一键，重复执行时撞唯一键自动跳过。**这是 D 类最终该有的形态**，但要求动作本身可去重。

### 3.2 四种幂等策略（任务声明用哪种）

| 策略 | 语义 | 适用 | 实现要点 |
|------|------|------|---------|
| `DELETE_INSERT` | 先按业务日期删除，再重建 | A 类 | 删除+重建必须在**同一事务**内 |
| `UPSERT` | 按唯一键覆盖写 | B 类 | 唯一键含 `biz_date` |
| `CONDITIONAL` | 只处理满足条件的记录，处理时条件更新 | C 类 | 更新语句带 `WHERE status = 原值` |
| `NONE` | 不幂等，禁止重跑 | D 类 | 接口层直接拒绝 |

> **当前圈人任务用的是 `DELETE_INSERT`**：`deletePending()` 只删 `status=0` 的记录再重建，已发券记录保留。这个「只删未处理部分」的做法值得推广 —— 它让「重跑」和「已产生的业务动作」解耦。

### 3.3 结论：任务契约必须显式声明

每个任务必须在注解上声明三件事，调度框架才能替它做正确的决策：

```java
@ShopTask(
    name = "cart-recall",
    cron = "0 0 4 * * ?",
    zone = "Asia/Shanghai",
    idempotentStrategy = IdempotentStrategy.DELETE_INSERT,
    rerunnable = true,
    lockStrategy = LockStrategy.REDIS,
    bizDateAware = true,          // 支持按业务日期重跑
    maxAttemptsPerDay = 3,        // 同一天最多跑 3 次
    alertOnEmptyResult = true     // 产出 0 行也要告警
)
```

**`bizDateAware` 是最关键的一个开关**。它要求任务签名统一为 `execute(LocalDate bizDate)`。不参数化业务日期，任务就只能重跑「今天」，历史补数无从谈起 —— 这是当前设计最大的结构性缺陷。

---

## 4. 调度框架选型（七个选项）

### 4.1 对比表

| 方案 | 定位 | 任务数适配 | 额外依赖 | 运维成本 | 重跑支持 | 界面 |
|------|------|-----------|---------|---------|---------|------|
| **A. 保持现状** | 每任务自己写 | 1~3 | 无 | 无 | 各写各的 | 无 |
| **B. 自研轻量层** ⭐ | 模板方法 + 注册表 | 3~20 | 无（复用 Redis） | 无 | ✅ 统一 | 需自建（简单列表） |
| **C. ShedLock** | 只解决锁 | 任意 | 1 依赖 + 1 表 | 无 | ❌ 不管 | 无 |
| **D. XXL-JOB** | 完整调度中心 | 5~100+ | 独立调度中心 + 库 | **中高** | ✅ 内置 | ✅ 完善 |
| **E. PowerJob** | 新一代调度，支持工作流 | 5~100+ | 同上 | 中高 | ✅ 内置 | ✅ 完善 |
| **F. Quartz** | 老牌，持久化 Job | 任意 | 1 依赖 + 11 张表 | 中 | 需自研 | 无 |
| **G. ElasticJob** | 强分片 | 大数据量 | ZooKeeper | 高 | 需自研 | 有控制台 |

### 4.2 逐项点评

**A. 保持现状** — 任务数 ≤3 时可以接受，超过之后样板代码的维护成本指数上升，且**能力不一致**（如今天 `OrderTimeoutTask` 就没锁）。**不建议**。

**C. ShedLock** — 它只做一件事：让 `@Scheduled` 在多实例下只跑一次，且锁语义比手写更正确（基于 `lock_until` 时间戳而非 TTL，不存在「任务跑超时导致锁提前释放」的问题）。
**但它不管日志、不管重跑、不管告警。** 适合「只想要个可靠的锁」的场景。可以作为方案 B 的一个可选替换件（把 `TaskLockManager` 的实现换成 ShedLock）。

**D. XXL-JOB** — 功能最全：任务管理界面、执行日志、失败重试、手动触发、GLUE 在线编辑、分片广播、告警。
**代价是要独立部署一个调度中心**（含自己的 MySQL 库），且引入了「调度中心挂了任务就全停」的新故障点。
**什么时候该上**：任务数 > 15，或需要「A 任务成功后触发 B」的编排能力，或需要分片把单任务拆到多机。

**E. PowerJob** — 定位类似 XXL-JOB，架构更现代（支持工作流 DAG、更好的分片），但生态和社区规模小于 XXL-JOB，出问题可参考的资料少。**同等条件下优先 XXL-JOB**。

**F. Quartz** — 引入 11 张表、集群配置繁琐、无自带界面。它的优势是复杂的 cron 表达式和持久化 Job 存储，本项目用不上。**不推荐**。

**G. ElasticJob** — 分片能力最强，但依赖 ZooKeeper，运维成本最高。**只有单任务数据量单机跑不动时才值得考虑**。

### 4.3 推荐：方案 B（自研轻量层）

**理由**：

1. **体量匹配** —— 单体应用，任务数预计在 10 上下。上调度中心的运维成本 > 收益。
2. **零新增依赖** —— Redis 已经在用，锁可以复用；不需要新部署任何东西。
3. **完全可控** —— 不引入外部框架的版本兼容、升级、故障排查成本。
4. **为未来留退路** —— 抽象层的 `TaskExecutor` 接口是稳定的，未来迁 XXL-JOB 时只换「调度驱动」，任务代码不动。

**但自研必须克制。** 只做四件事，多一件都不做：

| 做 | 不做 |
|---|------|
| ✅ 统一锁 | ❌ 任务依赖编排（A 成功触发 B） |
| ✅ 统一执行日志 | ❌ 分片广播 |
| ✅ 统一重跑 | ❌ 在线编辑代码（GLUE） |
| ✅ 统一告警 | ❌ 复杂的失败策略配置 |

一旦发现需要「不做」列表里的能力，就说明**该迁 XXL-JOB 了**，而不是继续自研。

---

## 5. 推荐方案：轻量任务平台设计

### 5.1 分层结构

```
spring-shop-task/                    # 新建模块（或放 common，但独立更清晰）
└── src/main/java/com/springshop/task/
    ├── annotation/
    │   └── ShopTask.java            # 任务声明注解（见 3.3）
    ├── core/
    │   ├── TaskExecutor.java        # 任务契约接口：execute(LocalDate bizDate)
    │   ├── AbstractScheduledTask    # 模板方法：锁→日志→执行→告警→释放
    │   ├── TaskContext.java         # 执行上下文：bizDate / triggerType / attemptNo / sourceLogId
    │   ├── TaskRegistry.java        # 启动时扫描 @ShopTask，注册到内存 + 校验
    │   └── TaskLockManager.java     # 统一锁（Redis 实现，可替换为 ShedLock）
    ├── entity/  TaskExecLog.java  TaskRerunLog.java
    ├── mapper/  TaskExecLogMapper.java  TaskRerunLogMapper.java
    ├── service/ TaskRerunService.java   TaskQueryService.java
    ├── alert/   TaskAlertNotifier.java  WebhookAlertNotifier.java
    └── controller/admin/ AdminTaskController.java
```

**为什么单独建模块**：`common` 的定位是「不依赖业务的通用能力」，而任务平台需要依赖 Redis、MyBatis-Plus、Security。放 `common` 会污染它的定位。独立模块还能让业务模块按需依赖。

### 5.2 统一执行日志表（替代每任务一张日志表）

```sql
CREATE TABLE `task_exec_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_name`     VARCHAR(64)  NOT NULL COMMENT '任务名',
    `biz_date`      DATE         NOT NULL COMMENT '业务日期（重跑的核心参数）',
    `trigger_type`  TINYINT      NOT NULL COMMENT '1 定时 / 2 手动 / 3 重跑 / 4 自动重试',
    `status`        TINYINT      NOT NULL COMMENT '0 失败 / 1 成功 / 2 执行中',
    `attempt_no`    INT          NOT NULL DEFAULT 1 COMMENT '第几次尝试',
    `source_log_id` BIGINT       DEFAULT NULL COMMENT '重跑自哪条记录，形成重跑链',
    `operator`      VARCHAR(64)  DEFAULT NULL COMMENT '触发人（手动/重跑时必填）',
    `start_time`    DATETIME     NOT NULL COMMENT '开始时间',
    `end_time`      DATETIME     DEFAULT NULL COMMENT '结束时间',
    `duration_ms`   BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
    `row_count`     INT          DEFAULT NULL COMMENT '产出/处理行数',
    `error_msg`     VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    `worker_id`     VARCHAR(64)  DEFAULT NULL COMMENT '执行实例标识，多实例排查用',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_biz_attempt` (`task_name`, `biz_date`, `attempt_no`),
    KEY `idx_task_exec_time` (`task_name`, `start_time`),
    KEY `idx_task_exec_status` (`status`, `start_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务执行日志';
```

**`uk_task_biz_attempt` 是防并发重跑的关键**：两个运维同时点重跑，第二个会撞唯一键直接失败，不会产生两次执行。

> ⚠️ **CI 红线**：索引名 `uk_task_biz_attempt` / `idx_task_exec_time` / `idx_task_exec_status` 需同步到
> `spring-shop-web/src/test/resources/db/migration-test/`，且与现有全部索引名**全局唯一**（H2 库级唯一）。

### 5.3 统一后台接口

```
GET  /api/admin/tasks                        任务列表（cron、上次结果、下次执行时间、可重跑性）
GET  /api/admin/tasks/{name}/logs            执行历史（分页）
GET  /api/admin/tasks/{name}/logs/{id}       单次执行详情（含重跑链）
POST /api/admin/tasks/{name}/run             手动触发（必须传 bizDate）
POST /api/admin/tasks/{name}/rerun           重跑（传 bizDate 或 sourceLogId）
GET  /api/admin/tasks/{name}/rerun-check     预检：该任务该日期是否可重跑 + 原因
```

**权限分离**（重要）：
- `task:list` / `task:logs` — 查看
- `task:run` — 触发**当天**任务
- `task:rerun` — 重跑**历史**日期（高风险，需单独授权）

### 5.4 迁移现有任务

```java
// 迁移后：CartRecallTask 只剩业务
@Component
@ShopTask(name = "cart-recall", cron = "0 0 4 * * ?", zone = "Asia/Shanghai",
          idempotentStrategy = DELETE_INSERT, rerunnable = true, bizDateAware = true)
public class CartRecallTask extends AbstractScheduledTask {

    @Override
    protected TaskResult doExecute(LocalDate bizDate) {
        RecallBuildResultVO r = cartRecallService.build(bizDate);
        return TaskResult.of(r.getTargetCount());
    }
}
```

**约 150 行样板代码消失，只剩 6 行。** 锁、日志、异常兜底、告警、手动触发全部由模板接管。

---

## 6. 重跑机制设计（本方案重点）

### 6.1 五种触发来源

| 来源 | 触发者 | 场景 | `trigger_type` |
|------|--------|------|---------------|
| 定时调度 | 系统 | 正常 cron | 1 |
| 自动重试 | 系统 | 瞬时异常，退避重试 | 4 |
| 人工补数 | 运维 | 昨天整个服务没起来，漏跑了 | 2 或 3 |
| 失败重跑 | 运维 | 跑了但失败 | 3 |
| 数据修复 | 运维 | 跑成功了但数据不对（如口径改了） | 3 |

### 6.2 六条安全护栏（缺一不可）

| # | 护栏 | 默认值 | 防的是什么 |
|---|------|--------|-----------|
| 1 | **`bizDate` 必须显式传** | 无默认值 | 防止「以为在重跑昨天、实际跑了今天」 |
| 2 | **唯一键防并发** | `uk_task_biz_attempt` | 两个运维同时点重跑 |
| 3 | **重跑窗口上限** | `max-rerun-days: 30` | 误操作覆盖 3 个月前的历史数据 |
| 4 | **单日重跑次数上限** | `max-attempts-per-day: 3` | 反复重跑打爆数据库 |
| 5 | **不可重跑任务直接拒绝** | `rerunnable = false` | 重复发券 / 重复发短信 |
| 6 | **权限分离** | `task:run` ≠ `task:rerun` | 任何人随手就能重跑历史 |

**预检接口（第 5.3 节的 `rerun-check`）很有价值**：运维点重跑前先调它，返回「可以重跑」或「不可重跑 + 具体原因（如：该任务声明为不可重跑 / 超出 30 天窗口 / 今日已重跑 3 次）」。**把护栏做在预检里，比执行时抛异常体验好得多。**

### 6.3 自动重试 vs 人工重跑（必须分开）

这是最容易做错的地方 —— **不是所有失败都值得重试**。

| 异常类型 | 自动重试 | 理由 |
|---------|---------|------|
| `QueryTimeoutException` / `TransientDataAccessException` | ✅ 重试 | 数据库瞬时抖动，重试大概率成功 |
| `RedisConnectionFailureException` | ✅ 重试 | 同上 |
| `DeadlockLoserDataAccessException` | ✅ 重试 | 死锁牺牲者，重试可解 |
| `BusinessException` | ❌ 不重试 | 业务规则不满足，重试还是失败 |
| 参数/配置错误 | ❌ 不重试 | 重试无意义，需要人改配置 |
| 数据脏（如空指针） | ❌ 不重试 | 需要人修数据 |

**退避策略**：`1min → 5min → 15min`，最多 3 次。三次都失败 → 停止重试 + **告警**（进入人工处理）。

> 当前 `CartRecallTask` 的实现是「失败后什么都不做」。加了自动重试之后，瞬时抖动可以自愈，这是实打实的可靠性提升。

### 6.4 重跑链可追溯

`source_log_id` 指向上一次失败的记录，形成链表：

```
#1001  2026-10-01  定时  失败  (SQL timeout)
  └─ #1005  2026-10-01  自动重试  失败  (SQL timeout)
       └─ #1012  2026-10-01  重跑 by 张三  成功  7,316 行
```

有了这条链，能直接回答三个问题：
- 这份数据重跑了几次？
- 每次为什么失败？
- 谁在什么时候触发的？

**没有 `source_log_id`，重跑就是一个黑盒** —— 你只能看到「今天跑了 5 次」，但不知道为什么。

---

## 7. 告警设计（当前最大盲区）

### 7.1 四类告警

| 告警 | 触发条件 | 为什么重要 |
|------|---------|-----------|
| **执行失败** | `status = 0` | 最基础 |
| **连续失败** | 同一任务连续 N 次失败（默认 2） | 避免偶发失败刷屏，又能发现真问题 |
| **产出 0 行** | `status = 1` 但 `row_count = 0` | ⚠️ **最隐蔽的故障**：任务「成功」了，但没产出数据。圈人跑成功但入池 0 条，比跑失败更危险 —— 因为它看起来是正常的 |
| **执行超时** | `duration_ms > 阈值`（默认 10 分钟） | 提前发现性能劣化 |

**「产出 0 行」这条必须做。** 它对应注解里的 `alertOnEmptyResult = true`。很多线上事故不是任务挂了，而是任务「正常跑完但什么都没做」。

### 7.2 实现

```java
public interface TaskAlertNotifier {
    void alert(TaskAlert alert);   // 企微 / 钉钉 / 邮件
}
```

起步只需一个 `WebhookAlertNotifier`（企微或钉钉群机器人 webhook，几行 HTTP 调用）。**通知失败不能影响任务本身** —— 告警调用必须自己 try/catch，且不能阻塞主流程（建议异步）。

---

## 8. 分期落地建议

### Phase 1 — 抽象层 + 迁移圈人任务（低风险，建议先做）

- 新建 `spring-shop-task` 模块
- `V8__task_platform.sql`：`task_exec_log` + `task_rerun_log`（主 + `migration-test` 副本，索引名全局唯一）
- `AbstractScheduledTask` + `@ShopTask` + `TaskRegistry` + `TaskLockManager`
- `AdminTaskController`（列表 / 日志 / 手动 / 重跑 / 预检）+ RBAC 菜单
- **`CartRecallTask` 迁移过去**，行为保持等价
- 告警：`WebhookAlertNotifier` + 四类告警
- 测试：单测（模板方法逻辑、护栏判定）+ 集成测试（四件套）

**验收标准**：`CartRecallTask` 迁移后，原有 15 个测试全绿 + 新增重跑护栏测试。

### Phase 2 — 迁移订单超时任务

- `OrderTimeoutTask` 迁移（**保持不加锁**，声明 `lockStrategy = NONE`）
- 这个任务 `bizDateAware = false`（它是 fixedDelay 的「持续扫描」型，没有业务日期概念）

> 这一条恰好验证了抽象层是否设计得对：**如果抽象层只能容纳「按天跑批」一种形态，说明抽象错了**。所以 `TaskExecutor` 需要支持两种形态：`bizDateAware = true`（按天）和 `false`（持续扫描）。

### Phase 3 — 评估是否迁 XXL-JOB

**触发条件（满足任一即评估）**：
- 任务数 > 15
- 需要「A 任务成功后触发 B」的编排
- 单任务数据量单机跑不动，需要分片
- 需要非技术人员自助管理任务（改 cron、看日志）

---

## 9. 风险清单

| 风险 | 影响 | 对策 |
|------|------|------|
| **自研做过头** | 变成半个 XXL-JOB，维护成本超过收益 | 严守 4.3 的「不做」清单；触到边界就迁 XXL-JOB |
| **副作用任务被误重跑** | 重复发券 / 资损 | `rerunnable = false` 默认值；预检接口；权限分离 |
| **重跑覆盖历史数据** | 数据不可恢复 | `max-rerun-days` 窗口；重跑前预检提示影响行数 |
| **`OrderTimeoutTask` 加锁影响时效** | 超时订单取消变慢 | 该任务声明 `lockStrategy = NONE`（其条件更新天然幂等） |
| **告警风暴** | 运维麻木 | 连续失败才告警；同类告警 5 分钟内合并 |
| **抽象层与业务耦合** | 业务模块被迫依赖 task 模块 | `spring-shop-task` 只依赖 common；业务模块可选依赖 |
| **H2 索引名冲突** | CI 挂 | 新索引名全局唯一化 + 同步 `migration-test` 副本 |
| **迁移期两个任务体系并存** | 一段时间内新旧两套 | 分两期迁移，每期独立验证 |
| **自动重试掩盖真问题** | 一直重试一直失败，无人处理 | 重试上限 3 次；超限后告警并停止 |

---

## 10. 需要你拍板

| # | 决策点 | 选项 | 我的建议 |
|---|--------|------|---------|
| 1 | **调度框架** | 保持现状 / **自研轻量层** / ShedLock / XXL-JOB / PowerJob / Quartz / ElasticJob | **自研轻量层**，任务数 >15 再评估 XXL-JOB |
| 2 | **锁实现** | 手写 Redis（现状）/ ShedLock | 起步沿用**手写 Redis**（零依赖）；若觉得锁不够可靠，换 ShedLock 只改 `TaskLockManager` 一个类 |
| 3 | **不可重跑任务的默认值** | 默认可重跑 / **默认不可重跑** | **默认不可重跑**，需要重跑的显式声明 —— 安全侧默认值 |
| 4 | **重跑窗口** | 7 / 30 / 90 天 | **30 天**，可配置 |
| 5 | **告警通道** | 企微 / 钉钉 / 邮件 / 暂不做 | 需要你提供 **webhook 地址**；若暂时没有，先把告警落 `task_exec_log` + 后台可见 |
| 6 | **是否现在就迁 `OrderTimeoutTask`** | 本期迁 / 放 Phase 2 | 放 **Phase 2**，先让圈人任务验证抽象层 |
| 7 | **模块归属** | 新建 `spring-shop-task` / 放 `spring-shop-common` | **新建模块**，避免污染 common 的「无业务依赖」定位 |

---

## 11. 一句话总结

**现状不是「调度」有问题，是「基础设施没有抽象」** —— 每个任务自己造一遍轮子，造得还不一样（`OrderTimeoutTask` 至今没有锁）。

**重跑机制的核心不是「再跑一次」，而是「跑得安全」** —— 先给任务分「可重跑 / 不可重跑」，再用六条护栏（业务日期必传、唯一键防并发、窗口上限、次数上限、不可重跑拒绝、权限分离）把它围起来。

**建议先做轻量层**：约 150 行样板代码消失，换来自动重试、重跑链追溯、四类告警。等任务数过 15 再上 XXL-JOB，届时任务代码不用改。
