# 架构增强路线图实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不动单体结构的前提下按序补齐 5 项运营能力：订单超时取消、可观测性、容器化交付、购物车 Redis 化、支付模块。

**Architecture:** 全部为增量改动，模块依赖方向不变（业务模块依赖 common，web 统一聚合，pay 依赖 order）。每阶段 = 一个 feature 分支 + 单一 PR，串行推进（阶段 3 可与 1/2 并行）。

**Tech Stack:** Spring Scheduling、Spring Boot Actuator + Micrometer Prometheus、Docker Compose、StringRedisTemplate（已有，starter 在 common）、模拟支付网关（预留真实第三方接口）。

**已核实现状**：`OrderServiceImpl.cancel()` 已含库存/销量回滚（restoreStock + decreaseSales，同一事务）；`OrderStatus.PENDING_PAYMENT(1)/CANCELLED(5)`；actuator 未引入；cart 为纯 DB 读写；错误码 6xxx 段未分配。

---

### 阶段 1：订单超时自动取消（定时任务）+ 详情缓存一致性修复

**Files:**
- Create: `spring-shop-order/src/main/java/com/springshop/order/task/OrderTimeoutTask.java`
- Modify: `spring-shop-order/**/service/OrderService.java`（+`markCancelledBySystem` 声明）
- Modify: `spring-shop-order/**/service/impl/OrderServiceImpl.java`
- Modify: `spring-shop-web/**/SpringShopApplication.java`（`@EnableScheduling`）
- Test: `spring-shop-order/src/test/java/**/OrderServiceImplTest.java`（新增 3 用例）

- [ ] **Step 1: 写失败的单测**（`@ExtendWith(MockitoExtension.class)` 模板）：超时单被取消且逐条调用 restoreStock/decreaseSales；status≠1 的单不处理；单笔抛异常不影响批次其余订单
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl spring-shop-order test -Dtest=OrderServiceImplTest`，预期 FAIL（方法不存在）
- [ ] **Step 3: 实现 systemCancel**：将现有 `cancel()` 的回滚逻辑抽私有方法共用；系统取消用**条件更新**防并发（`update orders set status=5, cancel_time=now where id=? and status=1`，影响 0 行即放弃，不抛错）
- [ ] **Step 4: 实现定时器**：`@Scheduled(fixedDelay = 60_000)`，LambdaQueryWrapper 查 `status=1 and create_time < now-30min`（超时分钟数 `order.timeout.cancel-minutes: 30` 入 yml），逐单 try/catch 调 systemCancel，失败仅 log.error
- [ ] **Step 5: 缓存一致性附加项**：下单成功与订单取消后，对涉及 productId 执行 `stringRedisTemplate.delete(RedisKeys.productDetail(productId))`（修复销量变更后详情缓存脏读；order 已传递依赖 common 的 redis starter，可直接注入）
- [ ] **Step 6: 全量验证 + 提交**：`mvn clean verify` 绿；手工造 1 笔 create_time=40min 前的待付款单，等 1 个调度周期，断言 status=5、sku 库存回涨、`product:detail:{id}` 被删。提交 `feat: 订单超时自动取消定时任务`

### 阶段 2：可观测性（Actuator 指标 + traceId 贯穿）

**Files:**
- Modify: `spring-shop-web/pom.xml`（+`spring-boot-starter-actuator`、`micrometer-registry-prometheus`）
- Modify: `spring-shop-web/src/main/resources/application.yml`（management 配置段）
- Create: `spring-shop-web/src/main/java/com/springshop/web/common/TraceIdFilter.java`
- Modify: `spring-shop-web/src/main/resources/logback-spring.xml`（pattern 加 `%X{traceId}`；无此文件则新建）
- Test: `spring-shop-web/src/test/java/**/TraceIdFilterIntegrationTest.java`

- [ ] **Step 1: 写失败的集成测试**：遵循 AGENTS 集成测试 4 件套模板，断言任意接口响应头含非空 `X-Trace-Id`
- [ ] **Step 2: 实现 TraceIdFilter**：`OncePerRequestFilter` 注册于 security 链之前；取请求头 `X-Trace-Id`，无则 `UUID` 短格式；`MDC.put("traceId", ...)` + 响应头回写 + finally 清理
- [ ] **Step 3: yml 暴露端点**：`management.endpoints.web.exposure.include: health,info,metrics,prometheus`；`management.endpoint.health.show-details: never`（生产安全默认）
- [ ] **Step 4: 验证 + 提交**：curl `/actuator/health` UP、`/actuator/prometheus` 含 `jvm_` 指标、接口日志行带 traceId 且响应头同值。提交 `feat: actuator 指标暴露与 traceId 贯穿`

### 阶段 3：Docker Compose 一键交付（可与阶段 1/2 并行）

**Files:**
- Create: `Dockerfile`（多阶段构建）
- Create: `docker-compose.yml`（app + mysql:8 + redis:6）
- Create: `.dockerignore`（`target/`、`.git`、`docs/`）

- [ ] **Step 1: Dockerfile**：`maven:3.9-eclipse-temurin-21` 构建（`-DskipTests`，测试走 CI）→ 运行层 `eclipse-temurin:21-jre`，EXPOSE 6001
- [ ] **Step 2: compose**：MySQL 用 `MYSQL_ROOT_PASSWORD=${MYSQL_PASSWORD}` 环境变量注入（禁止硬编码，见 AGENTS 禁止事项）；mysql/redis 数据 volume；app `depends_on: {mysql: {condition: service_healthy}}`，MySQL 带 healthcheck；Flyway 启动自动建表+种子
- [ ] **Step 3: 验证 + 提交**：`docker compose up -d` 后 `curl localhost:6001/api/health` 返回 ok，`redis-cli` 内可见 `product:detail:*` 回填。提交 `chore: docker compose 一键启动`

### 阶段 4：购物车 Redis 化（读加速，DB 为主存）

**Files:**
- Modify: `spring-shop-common/**/security/RedisKeys.java`（+`cart(userId)` → `cart:{userId}`）
- Modify: `spring-shop-cart/**/service/impl/CartServiceImpl.java`
- Test: `spring-shop-cart/src/test/java/**/CartServiceImplTest.java`（mock StringRedisTemplate 沿用现有模板）

- [ ] **Step 1: 设计定稿（写进 Impl 类注释）**：结构 `Hash cart:{userId}`，field=skuId，value=`quantity|checked`，TTL 7 天滑动过期；**DB 为主存，Redis 为读加速**：写路径=先 DB 后 Redis 同步（Redis 失败仅 log，靠回源兜底）；读路径=先 Redis，miss 回源 DB 并整 cart 重建
- [ ] **Step 2: 写失败单测**：列表命中缓存不查 DB（verify cartMapper 零调用）；miss 时回源并 `putAll` 重建
- [ ] **Step 3: 实现五个操作的双写**：加购/改数量/勾选/删除/清空，均 DB 事务提交后同步 Redis 对应 field（hset/hdel）
- [ ] **Step 4: 验证 + 提交**：`mvn clean verify` 绿；运行时加购后 `redis-cli HGETALL cart:1` 可见；手动 `DEL cart:1` 后列表接口自动回填。提交 `feat: 购物车 Redis 读加速`

### 阶段 5：支付模块 spring-shop-pay（模拟支付）

**Files:**
- Create: `spring-shop-pay/`（父 POM `<modules>` + `<dependencyManagement>` 注册；内部 controller/service/mapper/entity/dto/vo 分层；依赖 common + order）
- Create: `spring-shop-web/src/main/resources/db/migration/V{n}__pay_record.sql` + `migration-test/` 同步副本（**索引名全局唯一**，CI 红线）
- Modify: `spring-shop-common/**/result/ResultCode.java`（**申请 6000~6999 段**，并回填 AGENTS.md 错误码表）
- Modify: `spring-shop-order/**/service/OrderService.java`（+`markPaid(orderNo)`：status=1→2 条件更新）
- Modify: `spring-shop-web/**/security/SecurityConfig.java`（若 mockPay 走登录态则仅需 `/api/pay/**` 认证，无白名单）

- [ ] **Step 1: 建模块骨架 + 迁移脚本**：表 `pay_record`（id, order_no 唯一索引, user_id, amount, status, pay_time, create_time）；`mvn -pl spring-shop-pay -am compile` 通过
- [ ] **Step 2: 写失败单测**：支付成功写 record + 调 markPaid；非待付款单抛 PAY_STATUS_ILLEGAL(6xxx)；重复支付幂等返回成功（按 order_no 查到已成功 record 即直接返回）
- [ ] **Step 3: 实现**：`POST /api/pay/{orderNo}/mockPay`（`@PreAuthorize` 不加，走用户 JWT）；pay 校验归属与状态 → 写 record → 调 `orderService.markPaid`（其内部条件更新防并发，与超时任务天然互斥：仅 status=1 可被任一方处理）
- [ ] **Step 4: 验证 + 提交**：`mvn clean verify` 绿；mockPay 后订单变待发货、`pay_record` 有记录、重复调用幂等、超时任务不碰已支付单。提交 `feat: spring-shop-pay 模拟支付模块`

---

**PR 节奏**：阶段 1→2→3→4→5，分支命名 `feature/order-timeout-cancel`、`feature/observability`、`feature/docker-deploy`、`feature/cart-redis`、`feature/pay-module`；每 PR 单一功能、测试跟随、CI 绿后合入。

**风险与红线**：
- Flyway：只新增 V 脚本不改历史；每张新表同步 `migration-test/` 且索引名全局唯一
- Redis 写序：一律先 DB 后 Redis，Redis 失败静默降级（与现有缓存策略一致）
- 并发安全：系统取消与用户取消/支付共用「status=1 条件更新」语义，影响 0 行即放弃
- 阶段 4 双写引入的 Redis 故障面：回源逻辑保证 Redis 清空即全量重建，无数据丢失路径
