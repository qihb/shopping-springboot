# 购物车热度榜定时任务 — 技术方案（待确认）

> ⚠️ **需求定位已修订**：用户澄清真实目标是「对加购未买的用户发券刺激下单」，而非单纯做热度榜。
> 请以 **`2026-10-02-cart-recall-coupon.md`** 为准；本文档的「前 100 商品榜」降级为该方案里「选品」的一个中间步骤。
> 本文档仍然有效的部分：`cart_item` 表结构与能力的调研结论（第 1 节）、性能与 CI 约束（第 4/6 节）。
> **特别提示**：本文档第 1 节「加购→下单数据被删，所以榜单看不见转化好的商品」这一担忧，在召回发券的场景下**恰好反转成特性** —— 正因为下单即删，`cart_item` 里剩下的就是「加购未买」集合。

> **状态**：方案评审中，**未落地任何代码**。请先确认第 7 节的 4 个决策点，再进入实现。
> **需求原文**：每天凌晨 4 点，跑一遍所有用户的购物车数据，做数据分析，排出前 100 的商品。
>
> ⛔ **本文档已被取代，勿按此实施**（2026-10-08 核对）：真正的落地版本是
> `2026-10-02-cart-recall-coupon.md` + `2026-10-03-cart-recall-phase1-results.md`，
> 对应代码在 `spring-shop-stats`，迁移脚本是 **`V7__cart_recall.sql`**。
> 本文档里提到的 `V7__cart_hot_rank.sql`、`CartHotRankMapper.xml`、`/api/admin/stats/cart-rank/**`
> **均不存在**，V7 版本号已被 `cart_recall` 占用。

**Tech Stack（复用现有能力）**：Spring Scheduling（`@EnableScheduling` 已在 `SpringShopApplication` 上）、MyBatis-Plus + XML Mapper、Flyway、StringRedisTemplate、Spring Security 双过滤链 + RBAC。

**已核实的现状（方案基于这些事实，非假设）**：

| 事实 | 出处 |
|------|------|
| `@EnableScheduling` 已开启，已有定时任务先例 | `SpringShopApplication.java:12`、`order/task/OrderTimeoutTask.java:43` |
| 购物车表 `cart_item(user_id, sku_id, quantity, checked, create_time, update_time, is_deleted, version)`，唯一键 `uk_user_sku(user_id, sku_id)` | `V3__init_order_schema.sql:29-42` |
| 加购语义 = **同 SKU 累加数量**，不是插入新行 | `CartServiceImpl.add():117-124` |
| 下单后 / 清空 / 删除勾选 = **物理删除**（`CartItem` 类注释明确「物理删除才是正确语义」） | `CartServiceImpl.deleteChecked():361`、`clear():370` |
| `CartItem` 实体**未映射** `is_deleted`（无 `@TableLogic`） | `CartItem.java:17-37` |
| `cart_item` 上**只有** `uk_user_sku` 和主键，**没有 sku_id 单独索引** | `V3__init_order_schema.sql:40` |
| 目前**没有任何 XML Mapper**（`resources/mapper/**` 为空），复杂 SQL 走 XML 是新引入的能力 | Glob `**/resources/mapper/**/*.xml` = 空 |
| 错误码已占用：0-99 / 1000 / 2000 / 3000 / 4000 / 5000 / 6000 段 | `ResultCode.java` |
| 测试迁移脚本在 `spring-shop-web/src/test/resources/db/migration-test/`，索引名需全局唯一 | `application-test.yml:17`、`migration-test/V3` 头注释 |

---

## 1. 结论先行：这个需求有一个必须先拍板的前提

**`cart_item` 是「状态表」，不是「事件表」。** 这是整个方案里最大的坑，不解决它，做出来的榜大概率不是你要的东西。

具体后果：

1. **加购→下单这条最高价值的链路，数据被删掉了。**
   用户昨晚 20 点加购、22 点下单，凌晨 4 点跑批时这条记录已经不存在。也就是说「昨天最火、转化最好的商品」在这个榜上完全看不见，反而只有「加了没买」的商品才留得下来 —— 榜单会系统性地偏向低转化商品。

2. **榜单会被「僵尸购物车」长期占据。**
   一个商品被 3 个月前加购一次、之后没人动，它每天都贡献一份数据。榜单反映的是「历史累积存量」，不是「昨天的热度」，日与日之间几乎不变，没有分析价值。

3. **`quantity` 无法表达加购次数。**
   它只是当前累加值，「一次加 10 件」和「10 次各加 1 件」在表里长得一模一样。所以「加购频次」「加购转化率」这类指标根本算不出来。

4. **`create_time` / `update_time` 都不是「加购发生时间」。**
   `create_time` 是首次加购时间，`update_time` 是最后一次任意修改（改数量、改勾选都会刷新）。用它们过滤「昨天新增加购」会漏掉绝大多数真实加购行为。

**因此必须先选数据源口径**，它决定后面所有设计：

| 方案 | 口径 | 能回答的问题 | 代价 |
|------|------|-------------|------|
| **A. 购物车当前快照**（本次可交付） | 4 点时刻全量 `cart_item` | 「此刻在架商品里，谁被加购得最多」= 运营看板 | 几乎为零，复用现有表 |
| **B. 加购流水**（推荐中期做） | 新增 append-only 表 `cart_add_log`，每次加购插一条 | 「昨天发生了多少次加购、多少用户加了、加了多少件」= 真正的行为分析 | 加购写路径多一次 insert；新表 + 保留策略 |
| **C. 成交数据** | `order_item`（有 `create_time`、真实成交价） | 「昨天卖得最好的是谁」= 成交榜 | 零，但答的不是「购车」 |

**我的建议**：**榜单表结构一次设计到位 + 数据源口径可插拔**，一期先落 A 让链路和表跑通，**同时把 B 的流水埋点一起做掉**（加购处多一行 insert，成本极低），二期把口径切到 B。这样不返工，也避免一期做完发现「这榜没意义」。

> 如果你明确只要「当前购物车热度看板」，那 A 单独立项就够，B 可以不做 —— 这是第 7 节的第 1 个决策点。

---

## 2. 需求拆解与口径定义

### 2.1 排序指标设计

单指标都有明显缺陷，建议落 **3 个原始指标 + 1 个加权分**，原始指标全量入库（口径变了不用重扫全表）：

| 指标 | 计算 | 特点 |
|------|------|------|
| `add_user_count` | `COUNT(DISTINCT user_id)` | **抗刷单能力最强**，最稳，建议作为主排序键 |
| `add_quantity` | `SUM(LEAST(quantity, qty_cap))` | 反映购买意愿强度，但单用户可自己刷高 |
| `add_amount` | `SUM(LEAST(quantity, qty_cap) * sku.price)` | 反映 GMV 潜力，受单价影响大 |
| `score` | `w_user*用户数 + w_qty*件数 + w_amount*金额` | 综合分，权重可配 |

**默认建议**：主排序用 `add_user_count`（用户数），`add_quantity` 做 tie-break，`score` 作为可切换的备选口径。原因：`quantity` 完全由单个用户控制，1 个人就能把某个 SKU 刷到榜首；用户数需要多个真实账号才能刷，成本高得多。

### 2.2 防刷规则（阈值全部配置化）

- **单用户单 SKU 贡献封顶**：`LEAST(quantity, qty_cap)`，默认 5 件。超过部分不计入。
- **异常账号剔除**：购物车条目数 > 阈值的账号（羊毛党/爬虫的典型特征），默认 200。
- **长尾过滤**：`add_user_count < min_user_count` 的商品不进榜（默认 1，即不额外过滤）。
- **并列必须确定性**：`ORDER BY add_user_count DESC, add_quantity DESC, product_id ASC`。少一个 tie-break，每次跑批并列商品的顺序就会抖，前端看到的榜单会「跳动」。

### 2.3 过滤规则（必须与购物车列表口径一致）

只统计**有效条目**，规则完全对齐 `CartServiceImpl.isItemValid()`：

```sql
c.is_deleted = 0                    -- 安全网：cart_item 走物理删除，该列恒为 0
AND s.is_deleted = 0 AND s.status = 1   -- SKU 未删除且在售
AND p.is_deleted = 0 AND p.status = 1   -- 所属商品未删除且已上架
```

不这么做，榜上会出现已下架商品，运营照着榜单去推已经买不到的东西。

> ⚠️ 注意：`CartItem` 实体没有 `@TableLogic`，MyBatis-Plus **不会**自动拼 `is_deleted = 0`。走 XML 手写 SQL 时这个条件必须自己加（虽然当前恒为 0，但语义上不能省）。

**`checked` 是否参与统计**：建议**不参与**。`checked` 只是「结算勾选框」的当前状态，代表不了热度，而且用户全选/全不选会批量刷新它，把它算进指标会让榜单随用户操作剧烈波动。保留为可配项。

### 2.4 聚合粒度

「前 100 的商品」→ 建议 **SPU（商品）级**，把同一商品下的多个 SKU 汇总后再排序；同时保留该商品的 Top SKU 明细（谁贡献最多），供运营下钻。

如果你要的是「前 100 个 SKU」，那聚合粒度改成 `sku_id`，`product_id` 作为附属字段 —— 这是第 7 节第 3 个决策点。

---

## 3. 模块划分

遵循 `AGENTS.md` 的「新增业务模块约定」，建议**新建 `spring-shop-stats`**：

```
spring-shop-stats/                          # 新增：只读统计分析能力
└── src/main/java/com/springshop/stats/
    ├── entity/        CartHotRank            # 榜单快照表实体
    ├── mapper/        CartHotRankMapper      # 快照表 CRUD（继承 BaseMapper）
    │                  StatsAnalysisMapper    # 聚合查询（XML 复杂 SQL）
    ├── dto/           CartRankQuery          # 查询入参
    ├── vo/            CartRankItemVO / CartRankVO
    ├── service/       CartRankService + impl # 聚合 → 算分 → 落快照
    ├── task/          CartRankTask           # @Scheduled 定时入口（薄壳，只做调度+异常兜底）
    └── controller/admin/ AdminStatsController
└── src/main/resources/mapper/stats/CartHotRankMapper.xml   # 聚合 SQL 落在这里
```

**依赖**：`common` + `cart` + `product`（一期）；二期做成交榜再加 `order`。
**父 POM**：`<modules>` 注册 + `<dependencyManagement>` 声明版本；`spring-shop-web` 增加对它的依赖。

**为什么独立模块而不是塞进 `spring-shop-cart`**：
- 这是跨 `cart` / `product`（/`order`）的只读分析能力，放进 cart 会让购物车模块承担「分析」职责，职责边界糊掉；
- 后续要做成交榜、趋势对比、榜单告警，会持续膨胀，单独模块更好演进；
- `AGENTS.md` 明确给了新模块的约定和错误码段位，项目本来就是按模块切分协作的。

> **轻量替代方案**（如果你想少动 pom）：全部塞进 `spring-shop-cart` 的 `stats` 子包，cart 已经有 product 的只读依赖。代价是职责边界不清，二期加成交榜时会尴尬。**我倾向前者。**

---

## 4. 数据库设计

### 4.1 新增 V7 迁移：榜单快照表

```sql
-- Flyway 版本化迁移 V7：购物车热度榜快照表（spring-shop-stats）
-- 设计要点：
-- 1. 快照只增不改，按 stat_date 留历史，天然支持「日环比 / 趋势对比」；
-- 2. uk_rank_date_no(stat_date, rank_no) 兜底任务重复执行：同一天重复跑不会产生重复名次；
-- 3. 原始指标（用户数/件数/金额）与加权分全部落库，口径调整时无需重扫全表。
CREATE TABLE `cart_hot_rank` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stat_date`       DATE          NOT NULL COMMENT '统计日期（跑批日）',
    `rank_no`         INT           NOT NULL COMMENT '名次，从 1 开始',
    `product_id`      BIGINT        NOT NULL COMMENT '商品 id（SPU）',
    `product_name`    VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `main_image`      VARCHAR(255)  DEFAULT NULL COMMENT '商品主图（快照）',
    `category_id`     BIGINT        DEFAULT NULL COMMENT '分类 id',
    `add_user_count`  INT           NOT NULL DEFAULT 0 COMMENT '加购用户数',
    `add_quantity`    INT           NOT NULL DEFAULT 0 COMMENT '加购件数（已按单用户封顶）',
    `add_amount`      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '加购金额（元，按 SKU 现价估算）',
    `score`           DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '加权综合分',
    `top_sku_id`      BIGINT        DEFAULT NULL COMMENT '贡献最大的 SKU id',
    `top_sku_detail`  VARCHAR(512)  DEFAULT NULL COMMENT 'Top SKU 明细（JSON 串，便于下钻）',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rank_date_no` (`stat_date`, `rank_no`),
    KEY `idx_rank_date_product` (`stat_date`, `product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='购物车热度榜快照表';
```

> ⚠️ **CI 红线**：`uk_rank_date_no`、`idx_rank_date_product` 这两个名字必须在 `src/test/resources/db/migration-test/V7__cart_hot_rank.sql` 里**原样保留**且与现有全部索引名不冲突（H2 索引名是库级唯一）。已核实现有索引名集合：`idx_parent_id`、`idx_category_id`、`idx_status`、`idx_product_id`、`uk_sku_code`、`idx_user_id`(地址)、`idx_order_user_id`、`idx_order_status`、`idx_order_id`、`uk_order_no`、`uk_user_sku`、`idx_pay_record_user_id`、`uk_pay_record_order_no` —— 上述两个新名字无冲突。

### 4.2 `cart_item` 加索引（性能关键）

当前 `cart_item` 只有 `uk_user_sku(user_id, sku_id)`，而聚合是 `GROUP BY sku_id` —— **没有可用索引，必然全表扫描 + 临时表 + filesort**。

```sql
-- V7 同脚本内追加（或独立 V8，看你要不要把 DDL 拆开）
ALTER TABLE `cart_item` ADD KEY `idx_cart_item_sku_id` (`sku_id`, `quantity`);
```

用 `(sku_id, quantity)` 覆盖索引而不是单列 `sku_id`：聚合只需这两列，可以走**索引覆盖扫描**，不用回表。

> ⚠️ **两个必须注意的点**：
> 1. **大表加索引是重 DDL**。Flyway 在应用启动时执行，大表上会阻塞启动。MySQL 8 加二级索引本身支持 `ALGORITHM=INPLACE, LOCK=NONE`，但 Flyway 默认不带这些 hint。**生产环境建议**：先在低峰期手动 `ALTER TABLE ... ALGORITHM=INPLACE, LOCK=NONE, ADD KEY ...`，Flyway 脚本里用条件化写法或由 DBA 单独执行，别让启动流程扛 DDL。
> 2. `cart_item` 是**高频写入表**（每次加购/改数量都写），多一个索引会略微增加写放大。这个索引的收益（把跑批从分钟级降到毫秒级）远大于成本，但要知道代价。

---

## 5. 核心实现

### 5.1 聚合 SQL（XML Mapper）

**一条 SQL 出结果，绝不能把明细拉到 JVM 里聚合**（千万行会 OOM）：

```xml
<select id="selectHotCandidates" resultType="com.springshop.stats.vo.CartRankCandidate">
    SELECT
        p.id                                        AS productId,
        p.name                                      AS productName,
        p.main_image                                AS mainImage,
        p.category_id                               AS categoryId,
        COUNT(DISTINCT c.user_id)                   AS addUserCount,
        SUM(LEAST(c.quantity, #{qtyCap}))            AS addQuantity,
        SUM(LEAST(c.quantity, #{qtyCap}) * s.price)  AS addAmount
    FROM cart_item c
    JOIN product_sku s ON s.id = c.sku_id
                      AND s.is_deleted = 0 AND s.status = 1
    JOIN product p     ON p.id = s.product_id
                      AND p.is_deleted = 0 AND p.status = 1
    JOIN user u        ON u.id = c.user_id      <!-- 仅当需要剔除异常账号时 -->
    WHERE c.is_deleted = 0
    GROUP BY p.id, p.name, p.main_image, p.category_id
    HAVING COUNT(DISTINCT c.user_id) >= #{minUserCount}
    ORDER BY addUserCount DESC, addQuantity DESC, p.id ASC
    LIMIT #{candidateLimit}
</select>
```

**几个容易踩的点**：

- **`ONLY_FULL_GROUP_BY`**：MySQL 8 默认开启。`p.name` / `p.main_image` / `p.category_id` 不是聚合列，必须出现在 `GROUP BY` 里（虽然 `p.id` 是主键、理论上函数依赖可推导，但依赖这个特性容易在 H2 / 不同 `sql_mode` 下挂掉）。**显式全列 GROUP BY 最稳**，测试用 H2 也要过。
- **`LIMIT` 取候选集而非 Top100**：加权分在 Java 里算（可读性好、易调权重），所以 SQL 取 `topN × candidateMultiplier`（默认 100×5=500）个候选，Java 算完 `score` 再重排截断 100。
  > 严格说加权分排序可能被「候选集外的高分商品」挤掉（如果各指标排序差异很大）。默认参数下金额/件数与用户数高度相关，误差可忽略；如果你对准确性要求高，可以把加权分也写成 SQL 表达式一次算完 —— 这是第 7 节可以顺带确认的细节。
- **一致性**：单条 SQL 在 InnoDB 下是语句级一致性读，快照干净。**如果将来改成「分片多次聚合」**，跨语句就读不到一致快照了，要么包只读事务（`START TRANSACTION WITH CONSISTENT SNAPSHOT`），要么接受轻微不一致 —— 先记下这个坑，默认实现用不到。
- **可观测**：SQL 里加 `/* cart-hot-rank */` 注释，方便在慢查询日志里一眼定位。

### 5.2 定时任务

```java
@Component
@ConditionalOnProperty(prefix = "stats.cart-rank", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CartRankTask {

    private static final Logger log = LoggerFactory.getLogger(CartRankTask.class);

    /** 每天凌晨 4 点执行；zone 必须显式指定，见下方时区说明 */
    @Scheduled(cron = "${stats.cart-rank.cron:0 0 4 * * ?}",
               zone  = "${stats.cart-rank.zone:Asia/Shanghai}")
    public void buildCartHotRank() {
        // 1) 抢分布式锁（多实例场景），抢不到直接 return
        // 2) try { service.build(LocalDate.now()) } catch (Exception e) { log.error(...); 写任务日志 }
        // 3) finally { 释放锁 }
    }
}
```

任务类本身是**薄壳**：只做调度、锁、异常兜底、日志；真正的聚合与落库在 `CartRankService` 里，这样单测可以不启 Spring 容器直接测。

### 5.3 ⚠️ 时区：这是最容易在生产翻车的一点

`@Scheduled` 的 cron **使用 JVM 默认时区**，和 `spring.jackson.time-zone: Asia/Shanghai`（`application.yml:12`）**完全无关** —— 后者只影响 JSON 序列化。

容器镜像（`eclipse-temurin:21-jre`）默认时区通常是 **UTC**，届时你写的 `0 0 4 * * ?` 会在**北京时间中午 12 点**触发，而不是凌晨 4 点。而且这个 bug 在本地开发（macOS 是 CST）永远复现不出来，只在容器里出现。

**双保险**：
1. `@Scheduled(..., zone = "Asia/Shanghai")` 显式指定（代码层，推荐必做）；
2. 容器 `TZ=Asia/Shanghai` 环境变量 / Dockerfile 里 `ENV TZ=Asia/Shanghai`（环境层，防御性）。

顺带：跑批依赖服务器时钟，确保 NTP 已同步，否则「跑批日」会错位。

### 5.4 ⚠️ 多实例重复执行

`@EnableScheduling` 是**每个实例各自生效**的。将来水平扩容到 N 个节点，这个任务就会同时跑 N 次（浪费资源；如果将来加了「写下游」的副作用，还会造成数据问题）。

现在单实例没问题，但方案里要预留。三档选择：

| 方案 | 实现 | 评价 |
|------|------|------|
| **Redis 锁**（建议） | `SET stats:cart-rank:lock {instanceId} NX EX 3600`，抢不到直接 return；释放时用 Lua 校验持有者再 DEL | 项目已有 Redis，零新依赖。**Redis 挂掉时退化为「都执行」→ 靠幂等兜底**，不会漏跑 |
| ShedLock | 引入 `net.javacrumbs.shedlock`，新建 `shedlock` 表 | 语义最正确（锁到期自动可抢），但多一个依赖 + 一张表 |
| DB 锁 | `task_lock` 表 + 唯一键 + 条件更新抢占 | 无新依赖，但代码量比 Redis 锁大 |

**默认建议 Redis 锁**：与项目现有「Redis 只做加速、故障静默降级」的既有风格一致（见 `CartServiceImpl` 的缓存设计注释）。

### 5.5 ⚠️ 幂等：同一天跑 N 次结果必须一致

任务可能因**应用重启补跑、手动补数、锁失效重复触发**而多次执行。设计上做到幂等：

1. **先删后插，同一事务**：
   ```java
   // 短事务只包住写入，不包聚合扫描
   @Transactional
   public void saveSnapshot(LocalDate statDate, List<CartRankItem> items) {
       rankMapper.delete(new LambdaQueryWrapper<CartHotRank>().eq(CartHotRank::getStatDate, statDate));
       // 批量插入
   }
   ```
2. **表上 `uk_rank_date_no(stat_date, rank_no)` 兜底**，即使并发重复写也不会产生重复名次。
3. **绝对不要把聚合扫描放进事务里**。全表扫可能跑几十秒，长事务会撑大 undo log、持有 MDL，还可能拖慢其他写入。顺序是：`事务外聚合（只读）` → `短事务写入快照`。

### 5.6 异常处理与可观测性

`@Scheduled` 方法抛异常**不会杀掉调度线程**（下次 cron 照常触发），但异常会被框架吞掉、只打一行日志 —— 很容易出现「今天没出榜，但没人发现」。

必须做：
- 任务方法整体 `try/catch`，`log.error` 带完整上下文；
- 写**任务执行日志表** `stats_task_log(task_name, stat_date, status, start_time, end_time, duration_ms, row_count, error_msg)`，这是「为什么今天没榜」的唯一排查入口；
- **跑完校验 `rowCount == topN`**，0 行或明显少于预期时打 `WARN` —— 这通常意味着上游数据异常或 SQL 写错，比静默出个空榜强得多；
- 记录耗时，超过阈值（如 60s）告警。

`stats_task_log` 表可以并入 V7 一起建，也可以先只打日志、二期再落表。**建议一期就建**，成本一张表，收益是运维可查。

---

## 6. 性能、消费与运维

### 6.1 数据量与性能预期

`cart_item` 行数 ≈ 活跃用户数 × 平均车内容量。

| 规模 | 行数 | 预期耗时 | 结论 |
|------|------|---------|------|
| 1 万活跃用户 × 5 条 | 5 万 | 毫秒级 | 随便跑 |
| 100 万活跃用户 × 5 条 | 500 万 | 秒级（有覆盖索引） | 没问题 |
| 千万级 | 千万+ | 十几秒 ~ 分钟级 | 需要优化 |

**优化路径（按需启用，不要一上来就过度设计）**：

1. **覆盖索引**（已含在 V7）—— 解决 90% 的场景；
2. **结果缓存**：Top100 查询接口走 Redis 缓存 24h（快照表本身就很轻，可选）；
3. **分片聚合**（仅在超大表时）：按 `sku_id` 区间多次聚合写临时表再汇总 —— 注意这时跨语句快照一致性失效（见 5.1）；
4. **慢查询监控**：靠 SQL 注释定位。

**默认实现就用「单条 SQL + 覆盖索引」**，分片聚合作为降级开关预留。

### 6.2 结果消费（后台接口）

- `GET /api/admin/stats/cart-rank?date=2026-10-02&limit=100` —— 查某天的榜
- `GET /api/admin/stats/cart-rank/trend?productId=&from=&to=` —— 趋势（二期）
- `POST /api/admin/stats/cart-rank/rebuild?date=YYYY-MM-DD` —— **手动补数/重跑**（因为幂等，安全）

**安全**：走 `/api/admin/**` 链，自动受 `adminPrincipalAuthorizationManager()` 保护，**不需要改 `SecurityConfig` 白名单**；但要给管理员角色配置对应**菜单权限**（项目已有 RBAC 权限中心），并加 `@PreAuthorize`。

### 6.3 数据保留

100 行/天 = 3.65 万行/年，表本身可以永久保留。但 `top_sku_detail` JSON 串会让行变胖，建议**保留 90 天**，清理逻辑直接挂在同一个任务末尾（跑完顺手删 90 天前的），不额外加一个任务。

### 6.4 配置项（`application.yml`）

```yaml
stats:
  cart-rank:
    enabled: true                # 测试环境置 false，见 6.5
    cron: "0 0 4 * * ?"
    zone: Asia/Shanghai          # 必填，见 5.3
    top-n: 100
    candidate-multiplier: 5      # 候选集倍数，供 Java 端加权重排
    qty-cap: 5                   # 单用户单 SKU 数量封顶（防刷）
    min-user-count: 1            # 长尾过滤阈值
    max-cart-size: 200           # 异常账号剔除阈值
    retain-days: 90
    weight:
      user: 0.6
      quantity: 0.3
      amount: 0.1
```

### 6.5 ⚠️ 测试与 CI（按 `AGENTS.md` 硬约束，缺一项 CI 必挂）

1. **Service 单测** `CartRankServiceImplTest`（`spring-shop-stats/src/test/java`）
   模板：`@ExtendWith(MockitoExtension.class)` + `@Mock` Mapper + `@InjectMocks`，不启 Spring。
   用例：加权分计算正确、并列排序确定性、空数据不抛异常、`qtyCap` 封顶生效、异常账号被剔除。

2. **接口集成测试** `CartRankIntegrationTest`（`spring-shop-web/src/test/java`）
   **类头必须同时声明 4 项**（`AGENTS.md` 明确的踩坑实录）：
   `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`，
   并 `@MockBean StringRedisTemplate` + `@BeforeEach` mock `opsForValue()`。**缺一个必挂**。

3. **⚠️ 定时任务会在测试里真实注册**：`@EnableScheduling` 是全局的，`@SpringBootTest` 会真实注册所有 `@Scheduled`（现有 `OrderTimeoutTask` 每 60 秒已经在测试期间跑了 —— 这是既有的隐性噪音）。
   4 点 cron 在短测试里不会触发，但**正确做法是给任务加 `@ConditionalOnProperty`，并在 `application-test.yml` 里 `stats.cart-rank.enabled: false`**，避免以后调 cron 或加 `fixedDelay` 类任务时污染测试。

4. **迁移脚本双份**：主 `spring-shop-web/src/main/resources/db/migration/V7__cart_hot_rank.sql` + 同步副本 `spring-shop-web/src/test/resources/db/migration-test/V7__cart_hot_rank.sql`，**索引名全局唯一**（见 4.1 的红线说明）。

5. **错误码段位**：已占用 0-99 / 1000 / 2000 / 3000 / 4000 / 5000 / 6000。**建议统计模块申请 7000~7999 段**（如 `STATS_RANK_DATE_INVALID(7001)`、`STATS_REBUILD_FAILED(7002)`），并在 `AGENTS.md` 错误码表回填。

6. **H2 兼容性检查**：`LEAST()`、`COUNT(DISTINCT ...)`、`DATE` 类型在 H2 MySQL 模式下都支持；`DATE_FORMAT` 之类 MySQL 专有函数**要避免**（H2 不认）。日期参数用 `LocalDate` 直接绑定，不要做函数转换。

---

## 7. 需要你拍板的 4 个点

| # | 决策点 | 选项 | 我的建议 |
|---|--------|------|---------|
| 1 | **数据源口径** | A 购物车当前快照 / B 加购流水（需加埋点表）/ C 成交数据 | **A 先落地 + B 一起埋点**，榜单口径可切换。若只想要「当前热度看板」，A 单独立项 |
| 2 | **排序指标** | 加购用户数 / 加购件数 / 加购金额 / 加权分 | **加权分（用户数权重 0.6 为主）**，三个原始指标全入库，随时可换口径 |
| 3 | **聚合粒度** | SPU 商品级（前 100 个商品）/ SKU 级 | **SPU 级**，附 Top SKU 明细供下钻 |
| 4 | **多实例锁** | Redis 锁 / ShedLock / 暂不做（单实例） | **Redis 锁**（零新依赖，故障时靠幂等兜底） |

---

## 8. 实施步骤（确认后按此落地）

**Phase 1 — 本期交付**
1. 新建 `spring-shop-stats` 模块骨架：父 POM 注册 modules + dependencyManagement，`spring-shop-web` 加依赖，`mvn -pl spring-shop-stats -am compile` 通过
2. 写 `V7__cart_hot_rank.sql`（榜单快照表 + `stats_task_log` + `cart_item` 索引）+ `migration-test/` 同步副本（索引名全局唯一）
3. 写 `CartHotRank` / `StatsTaskLog` 实体、Mapper 接口、`CartHotRankMapper.xml` 聚合 SQL
4. 写失败的 Service 单测 → 实现 `CartRankServiceImpl`（聚合 → 算分 → 排序 → 短事务落快照 → 清理过期）
5. 实现 `CartRankTask`（`@Scheduled` + `zone` + Redis 锁 + try/catch + 任务日志）
6. 实现 `AdminStatsController`（查询 / 补数），配 RBAC 菜单 + `@PreAuthorize`
7. 写 `CartRankIntegrationTest`（4 件套齐全）→ `mvn clean verify` 绿
8. 手工验证：`POST /api/admin/stats/cart-rank/rebuild` 跑一次，断言快照表 100 行、名次连续、`stat_date` 正确；重复调用结果一致（幂等）
9. 上线策略：`enabled: false` 部署 → 手动 rebuild 验证 SQL 与数据 → 改 `true` 打开定时

**Phase 2 — 建议紧接着**
10. 新增 `cart_add_log` 加购流水表，`CartServiceImpl.add()` 同事务写一条流水
11. 榜单口径切到「昨日加购行为」，保留快照口径作为对照

**Phase 3 — 可选**
12. 成交榜（`order_item` 口径）、榜单趋势对比接口、榜单变化告警（新进榜/掉榜推送）

---

## 9. 风险清单（一页速览）

| 风险 | 影响 | 对策 |
|------|------|------|
| `cart_item` 是状态表，下单即删 | 榜单系统性偏向低转化商品，无日间变化 | 明确口径（决策点 1），二期补加购流水 |
| `@Scheduled` cron 用 JVM 时区 | 容器 UTC 下 4 点变 12 点，本地无法复现 | 显式 `zone` + 容器 `TZ` |
| 多实例重复执行 | 资源浪费 / 潜在重复副作用 | Redis 锁 + 幂等双保险 |
| 任务重复触发（重启补跑/手动） | 重复数据、名次冲突 | 先删后插同事务 + `uk_rank_date_no` |
| `cart_item` 无 `sku_id` 索引 | 全表扫 + filesort，大表跑批慢 | V7 加 `(sku_id, quantity)` 覆盖索引 |
| 大表加索引阻塞启动 | Flyway 启动时执行 DDL，可能卡住启动 | 低峰期手动 online DDL，或 DBA 单独执行 |
| `ONLY_FULL_GROUP_BY` / H2 差异 | 测试或线上 SQL 报错 | 全列 GROUP BY，避开 MySQL 专有函数 |
| 测试中定时任务真实注册 | 污染测试、CI 不稳定 | `@ConditionalOnProperty` + test profile 关闭 |
| 任务静默失败 | 「今天没出榜」无人发现 | `stats_task_log` + rowCount 校验 + 耗时告警 |
| 异常/长事务 | 长事务撑大 undo、持有 MDL | 聚合在事务外，只让写入进短事务 |
