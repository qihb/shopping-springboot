# 加购未买召回发券 — 技术方案（待确认）

> **状态**：Phase 1「圈人」**已落地并验证通过**（`mvn clean verify` BUILD SUCCESS），实测数据见
> **`2026-10-03-cart-recall-phase1-results.md`**。Phase 2（券体系）/ Phase 3（触达）仍待拍板。
> **需求**：统计「在购物车里但没买」的数据，给这批用户发优惠价，刺激下单。
> **本文档取代** `2026-10-02-cart-hot-rank-job.md` 的需求定位 —— 那版把「前 100 商品榜」当成了终点，实际它只是本方案里「选品」的一个中间步骤。

---

## 1. 结论先行

### 1.1 好消息：购物车表恰好就是「加购未买」集合，口径天然吻合

我上一版担心的「下单后数据被删」，在**你这个需求下反而是特性**。

代码证据（`OrderServiceImpl.create()`）：

```java
// OrderServiceImpl.java:139-141 —— 下单成功即物理删除本次下单的购物车条目
cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
        .eq(CartItem::getUserId, userId)
        .in(CartItem::getId, checkedItems.stream().map(CartItem::getId).toList()));
```

也就是说：**`cart_item` 表里剩下的，按定义就是「加购了但还没下单」的条目。** 你要的数据，现成就在这张表里，不需要新建数据源。

更妙的是 `create_time` 正是你要的字段：加购时写入，之后改数量/改勾选只会刷 `update_time`（`CartServiceImpl.updateQuantity()` 走 `updateById`），**`create_time` 始终保持「首次加购时刻」**。所以：

```sql
-- 「加购超过 24 小时还没买」= 真正值得召回的弃购
WHERE c.create_time < NOW() - INTERVAL 24 HOUR
```

这个口径是**准确的**，可以直接用。

### 1.2 坏消息：这条链路要的三块基础设施，项目里一块都没有

我全项目核实过：

| 缺什么 | 现状 | 证据 |
|--------|------|------|
| **优惠券 / 促销体系** | **完全没有** | `OrderServiceImpl.java:124` 注释：「暂无优惠券/运费，实付与应付相等（YAGNI）」 |
| **下单时的优惠计算** | 没有，实付 = 商品总额 | `order.setPayAmount(totalAmount)`，订单表也没有 `discount_amount` 字段 |
| **消息触达通道** | 没有，全项目零相关代码 | grep `coupon\|优惠券\|短信\|sms\|订阅消息\|notify\|推送` → 只命中上面那行注释 |
| 用户触达标识 | ✅ 有 `phone`（**可空**）+ `openid` | `User.java:33,36`；V1 `phone VARCHAR(20) DEFAULT NULL` |

所以「发优惠价」这四个字背后，是要新建一整套东西：

1. **券体系**：券模板 + 券实例 + 发放 + 核销 + 过期
2. **下单用券**：改 `OrderServiceImpl.create()` 的金额计算，订单表加 `discount_amount` / `coupon_id`，**会波及订单快照、支付金额（`pay_record.amount`）、退款**
3. **触达通道**：短信服务商 或 微信订阅消息 —— **这是外部依赖，要账号、要资质、要钱**
4. **发券幂等 + 频控 + 退订黑名单**

**这不是一个小改动，是一个中型项目。** 而且第 3 项涉及合规（营销短信需用户明示同意，《个人信息保护法》约束）。

### 1.3 所以我的建议：分四期，先做零风险的那一步

**Phase 1 只做「圈人」，不发券、不触达。** 先把人群包跑出来落库，用真实数据回答一个前置问题：**到底有多少人、多少人值得发、发券成本能不能收回来？**

这一步零外部风险、零外部依赖，做完你才有依据决定要不要投 Phase 2/3。**先验证值不值得做，比先做出来更重要。**

---

## 2. 「加购未买」的三个盲区（必须先知道）

虽然购物车表口径天然吻合，但它是**当前状态快照**，有三个能力边界：

### 盲区 1：无法归因，活动效果算不出来

这是最要命的一条。完整链路是「发券 → 用户下单 → 核销」，但**用户一下单，`cart_item` 那条记录就被删了**。结果是：

- 你无法知道这张券是发给哪个商品的
- 你无法知道发券后有没有转化（记录没了）
- 你无法算核销率、ROI、券面额该给多少

**没有加购流水表（append-only），整个营销活动是黑盒，只能看「发出多少张券」，看不到「赚回多少钱」。**

> 建议：Phase 2 就补一张 `cart_add_log` 加购流水表（`CartServiceImpl.add()` 里多一次 insert，成本极低），配合 `coupon_grant` 发券记录，才能做闭环归因。**这件事越早做越好，因为它只能记录「从埋点之后」的行为，晚做一天就少一天的数据。**

### 盲区 2：用户自己删了商品，就再也找不回来

用户加购 → 10 天没买 → 手动删掉 → 记录消失。你想在「加购第 7 天」发券，但他第 6 天删了，你就丢了这个人。同理，用户下单后反悔取消，也不会回到购物车。

### 盲区 3：「没买」不代表「想买」

购物车里的东西，成分很杂：随手加购、比价中、等降价、已经别处买了、纯粹收藏。**不加筛选地群发，大部分预算会烧在无效人群上。**

---

## 3. 关键设计：券该发给谁

### 3.1 先确认一个根本分歧：商品券 vs 千人千券

你说「统计在购物车里没买的，然后**给他**发优惠价」——「他」指的是用户还是商品？这决定券模型完全不同：

| 模式 | 逻辑 | 券模型 | 成本 |
|------|------|--------|------|
| **A. 商品定向券** | 选出前 100 个「加购未买」热门商品，对这 100 个商品的所有加购用户发券 | 商品券 + 商品池 | 可控，只涉及 100 个商品 |
| **B. 千人千券** | 每个用户，针对他自己车里那件商品，发一张专属券 | 用户券 + 单商品定向 | 精细，但发券量大、系统复杂 |

**我倾向 A 起步**：成本可控、券模型简单、能复用你最初要的「前 100 商品」。B 是 A 跑通后的自然演进。

### 3.2 一个更有价值的选品指标：弃购率

单纯按「加购人数」排前 100，选出的是**热门商品**，但热门商品本来就好卖，**给它打折是白送利润**。

真正值得发券的是：**很多人加购、但很少人成交** —— 这说明用户想买、但卡在价格上，券能直接撬动。

```
弃购率 = 加购未买用户数 / (加购未买用户数 + 已成交用户数)
```

两个数据源都在：
- **加购未买用户数** ← `cart_item`（没下单的还在表里）
- **已成交用户数** ← `order_item`（有 `create_time`，可按时间窗过滤；注意要排除已取消订单）

**选品逻辑**：`加购用户数` 达到一定基数 **且** `弃购率` 高 → 优先发券。
再叠加**加购金额**做预算排序（大额弃购订单，一张券能撬动更多 GMV）。

> 这个指标比「前 100 热门商品」有用得多 —— 建议纳入。

### 3.3 人群筛选规则（防烧钱，阈值全部配置化）

| 规则 | 默认值 | 目的 |
|------|--------|------|
| 加购时长 ≥ N 小时 | 24h | 刚加购就发券是骚扰，且用户本来可能就要买 |
| 加购时长 ≤ M 天 | 7 天 | 超过一周的「僵尸购物车」，用户早忘了，打开率极低 |
| 商品仍在售且价格未变 | 强校验 | 下架/涨价了发券没意义，还会引发投诉 |
| 加购件数 / 金额下限 | ≥ 2 件 或 ≥ 阈值 | 过滤「随手加购一件」的低意向人群 |
| 用户有手机号或 openid | 必须 | 否则无法触达，发券也送不到 |
| 用户近 N 天未收过券 | 30 天 ≤ 2 次 | 频控，防薅羊毛和投诉 |
| 用户状态正常 | `status = 1` | 排除已禁用/注销账号 |
| 排除黑名单 | 退订用户 | 合规要求 |

---

## 4. 现状盘点：改动会波及哪些地方

| 模块 | 改动 | 风险 |
|------|------|------|
| `spring-shop-stats`（新建） | 圈人跑批 + 人群包落库 + 后台查询 | 低，纯新增只读能力 |
| `spring-shop-coupon`（新建，Phase 2） | 券模板/实例/发放/核销 | 中，全新模块 |
| `spring-shop-order`（Phase 2） | `create()` 金额计算加优惠；`orders` 表加 `discount_amount`/`coupon_id` | **高**：影响订单快照、支付金额 |
| `spring-shop-pay`（Phase 2） | `pay_record.amount` 需取优惠后金额 | **高**：金额算错会直接产生资损 |
| `spring-shop-cart`（Phase 2） | `add()` 增加购流水写入 | 低 |
| 触达通道（Phase 3） | 短信服务商 / 微信订阅消息 | **高**：外部依赖 + 合规 + 费用 |
| `SecurityConfig` | 新后台接口走 `/api/admin/**` 链，**无需改白名单**；但需配 RBAC 菜单 | 低 |

⚠️ **金额链路是最高风险区**：`total_amount` / `pay_amount` / `discount_amount` 三者关系一旦不严谨，就是资损事故。建议 Phase 2 单独立项、单独评审、必须有金额对账测试。

---

## 5. 表设计

### 5.1 Phase 1：待召回人群池

```sql
-- 当前待召回池：每天跑批滚动刷新（不是按天快照，避免行数爆炸）
-- 100 万用户 × 1.5 条 = 150 万行/天，按天存会失控，因此只保留「当前池」
CREATE TABLE `cart_recall_target` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT        NOT NULL COMMENT '用户 id',
    `sku_id`           BIGINT        NOT NULL COMMENT 'SKU id',
    `product_id`       BIGINT        NOT NULL COMMENT '商品 id',
    `product_name`     VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `main_image`       VARCHAR(255)  DEFAULT NULL COMMENT '商品主图（快照）',
    `sku_price`        DECIMAL(10,2) NOT NULL COMMENT '加购时商品现价（快照）',
    `quantity`         INT           NOT NULL COMMENT '加购数量',
    `add_time`         DATETIME      NOT NULL COMMENT '首次加购时间（cart_item.create_time）',
    `idle_hours`       INT           NOT NULL COMMENT '已闲置小时数',
    `suggested_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '建议券面额',
    `status`           TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 已发券 / 2 已转化 / 3 已失效',
    `stat_date`        DATE          NOT NULL COMMENT '最近一次跑批日',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_user_sku` (`user_id`, `sku_id`),
    KEY `idx_recall_stat_status` (`stat_date`, `status`),
    KEY `idx_recall_product` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='加购未买待召回人群池';
```

> ⚠️ **CI 红线**：`uk_recall_user_sku` / `idx_recall_stat_status` / `idx_recall_product` 需同步到 `spring-shop-web/src/test/resources/db/migration-test/V7__cart_recall.sql`，且与现有全部索引名**全局唯一**（H2 库级唯一）。已核实现有索引名集合无冲突。

### 5.2 选品结果表（保留「前 100 商品」）

```sql
-- 每日选品结果：加购未买 TOP N 商品 + 弃购率，只存聚合结果（很小，可长期保留）
CREATE TABLE `cart_recall_product` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stat_date`         DATE          NOT NULL COMMENT '统计日期',
    `rank_no`           INT           NOT NULL COMMENT '名次',
    `product_id`        BIGINT        NOT NULL COMMENT '商品 id',
    `product_name`      VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `abandon_user_cnt`  INT           NOT NULL COMMENT '加购未买用户数',
    `paid_user_cnt`     INT           NOT NULL DEFAULT 0 COMMENT '已成交用户数',
    `abandon_rate`      DECIMAL(6,4)  NOT NULL DEFAULT 0 COMMENT '弃购率',
    `abandon_amount`    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '加购未买金额',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_prod_date_rank` (`stat_date`, `rank_no`),
    KEY `idx_recall_prod_date_pid` (`stat_date`, `product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='加购未买选品结果表';
```

### 5.3 Phase 2/3 预留表

- `coupon_template`（券模板：类型/面额/门槛/有效期/适用范围）
- `coupon`（券实例：持有人/状态/有效期）
- `coupon_grant`（发券记录：**归因的核心**，记录发给谁、为什么发、哪次活动）
- `cart_add_log`（加购流水，append-only，用于行为分析）
- `orders` 加 `discount_amount` / `coupon_id` 字段

---

## 6. 跑批任务设计（Phase 1）

沿用上一版方案已验证的骨架，改动点只有「口径」：

1. **`@Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Shanghai")`** —— ⚠️ **时区必须显式指定**。cron 用 JVM 默认时区，容器（`eclipse-temurin:21-jre`）默认 UTC，你的凌晨 4 点会变成北京时间中午 12 点，且本地 macOS 是 CST **永远复现不出来**。
2. **Redis 分布式锁** —— `@EnableScheduling` 每个实例各自生效，扩容后会跑 N 次。
3. **幂等** —— 滚动刷新池子：`delete from cart_recall_target where status = 0`（只清未处理的，已发券的记录要留）+ 批量插入，同一短事务。
4. **聚合在事务外** —— 全表扫可能几十秒，绝不能包进事务；只让写入进短事务。
5. **`@ConditionalOnProperty` + `application-test.yml` 关闭** —— `@EnableScheduling` 是全局的，`@SpringBootTest` 会真实注册所有 `@Scheduled`（现有 `OrderTimeoutTask` 每 60 秒已经在测试期间跑了，这是既有的隐性噪音）。
6. **任务日志表 + rowCount 校验** —— 「今天没出人群包」必须能被发现。
7. **覆盖索引** —— `cart_item` 目前只有 `uk_user_sku(user_id, sku_id)`，`create_time` 过滤无索引，需补 `(create_time)` 或 `(checked, create_time)`。

---

## 7. 分期落地建议

### Phase 1 — 圈人（低风险，建议先做这个）

**产出**：`cart_recall_target` 人群池 + `cart_recall_product` 选品结果 + 后台查询/手动触发接口

**Files**：
- 新建 `spring-shop-stats` 模块（依赖 common + cart + product + order）
- `V7__cart_recall.sql`（主 + `migration-test` 副本，索引名全局唯一）
- `CartRecallTask` / `CartRecallService` / `CartRecallTargetMapper` + XML 聚合 SQL
- `AdminStatsController`（查询 / 手动重跑），配 RBAC 菜单
- `ResultCode` 申请 **7000~7999 段**（已占用 0-99/1000/2000/3000/4000/5000/6000）
- 测试：Service 单测（`@ExtendWith(MockitoExtension.class)`）+ 集成测试（`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional` + `@MockBean StringRedisTemplate`，**四件套缺一 CI 必挂**）

**先回答的问题**：有多少人？加购时长分布？多少人无手机号无法触达？前 100 商品的弃购率长什么样？**值不值得做 Phase 2？**

### Phase 2 — 券能力（中风险，单独立项）
`spring-shop-coupon` 模块 + 下单用券改造 + `cart_add_log` 加购流水埋点。**金额链路必须单独评审 + 对账测试。**

### Phase 3 — 主动触达（高风险，需外部资源）
短信服务商 / 微信订阅消息接入 + 发券记录 + 频控 + 退订黑名单 + **合规确认**。

### Phase 4 — 归因调优
发券 → 下单 → 核销漏斗统计、券面额 A/B 测试、ROI 复盘。

---

## 8. 风险清单

| 风险 | 影响 | 对策 |
|------|------|------|
| **无归因能力** | 活动是黑盒，不知道赚没赚回来 | Phase 2 补 `cart_add_log` + `coupon_grant`；**埋点越早越好，晚了补不回来** |
| **训练用户「加购等券」** | 拉低客单价，原价买过的用户不满 | 券面额小（5% 量级）、限时 24h、限品、每人 30 天 ≤ 2 次、不公开规则 |
| **群发烧钱** | 预算砸在低意向人群 | 3.3 节的 8 条筛选规则全部落地 |
| **营销短信合规** | 法律风险 | 需用户明示同意 + 退订机制；Phase 3 前确认 |
| **触达覆盖率低** | `phone` 可空，大量用户触达不到 | Phase 1 先统计覆盖率，决定是否走小程序订阅消息 |
| **金额算错** | **直接资损** | 订单/支付金额改造单独立项 + 对账测试 |
| **僵尸购物车** | 打开率极低、投诉 | 加购时长上限 7 天 |
| `@Scheduled` 时区 | 4 点变 12 点，本地无法复现 | 显式 `zone` + 容器 `TZ` |
| 多实例重复跑 | 重复发券 | Redis 锁 + 发券幂等（唯一键） |
| 大表加索引阻塞启动 | Flyway 启动时 DDL 卡住 | 低峰期手动 online DDL |

---

## 9. 需要你拍板

| # | 决策点 | 选项 | 我的建议 |
|---|--------|------|---------|
| 1 | **本期范围** | 只做圈人 / 一路做到发券 | **先只做圈人**，用真实数据验证规模和价值再决定投入 |
| 2 | **券模型** | A 商品定向券（对 TOP100 商品发）/ B 千人千券（对每个用户的加购商品定向） | **A 起步**，B 作为演进 |
| 3 | **选品口径** | 按加购人数 / 按**弃购率** | **弃购率 + 加购基数**，比单纯热门商品更有效 |
| 4 | **触达通道** | 短信 / 微信订阅消息 / 先站内可见 | 需先确认 **Phase 1 统计出的手机号覆盖率**再定 |
| 5 | **加购流水埋点** | 本期就做 / 放到 Phase 2 | **本期就做**，它只能记录埋点之后的行为，晚一天少一天数据 |
