# MEMORY.md — spring-shop 项目长期记忆

> 只记「跨会话仍然成立」的项目约定与判断，不记流水账（流水账在 `YYYY-MM-DD.md`）。

## 缓存一致性：测试必须断言「行为」，不能只断言结果

**背景（2026-10-08）**：下单后购物车 Redis 缓存未失效的 bug（`OrderServiceImpl.create()`
直接用 `cartItemMapper.delete` 绕过 `CartServiceImpl`），
`GET /api/cart` 会在 7 天滑动 TTL 内一直返回已下单的条目。

**为什么它能漏到线上**：`OrderIntegrationTest` 与 `CartIntegrationTest` 都用
`@MockBean StringRedisTemplate` 把 Redis 整个 mock 掉（测试环境无 Redis）。
缓存永远 miss → 读路径永远回源 DB → **脏读在集成测试里根本不会出现**。
`OrderIntegrationTest` 里那条 `assertEquals(0, cartItems(token).size())` 断言，
在修复前后都是绿的。

**因此的项目约定**：

- 任何**缓存写入/失效**相关的改动，单测必须断言**「失效方法被调用」**
  （如 `verify(stringRedisTemplate).delete(RedisKeys.cart(userId))`），
  而不能只断言最终可观测结果 —— 后者会被 `@MockBean` 掩盖。
- 想验证真实的缓存一致性，只能**真连 Redis 实跑**（本机 MySQL + Redis 都在跑，
  起一个独立端口的实例做 A/B 对照是最省事的办法）。
- 参考实现：`OrderServiceImplTest#create_should_evict_cart_cache`。

## 缓存失效的责任边界

`CartServiceImpl` 自己的写路径（`add` / `updateQuantity` / `updateChecked` / `delete` /
`deleteChecked` / `clear`）都会维护 `RedisKeys.cart(userId)`；
**但绕过它、直接操作 `cartItemMapper` 的地方必须自己补失效**。
已知会绕过它的地方：`OrderServiceImpl.create()`（下单删勾选行）—— 已修（`895b976`）。
新增任何直接操作 `cart_item` 的代码时，先想一遍要不要失效 `cart:{userId}`。

## MCP 方案：已评估并否决（2026-10-08）

REST→MCP **不采用**，实验终止。**不要再评估或引入 MCP。**

理由：
1. 唯一「零代码 + 真 HTTP 链路」都成立的方案（实验二 mcp-link）是个 **Go 写的旁路进程**，
   与项目 Java / Spring Boot 技术栈不符，等于多一套异构运行时与运维负担。
2. 唯一同栈的方案（实验四 Spring AI 进程内）不经 HTTP 过滤链 → 拿不到 JWT 身份 →
   **写操作做不了**，只能暴露只读工具；且 `/sse` 无鉴权。属结构性限制。

完整实测数据与踩坑存档：`docs/superpowers/plans/2026-10-06-rest-to-mcp-evaluation.md`
（开头已标记「终止，勿执行」）。MCP 评估技能 `skills/spring-shop-mcp-eval/`
**已于 2026-10-08 按用户要求删除**（项目 skills 目录现只剩 `spring-shop-excel-task`）。

## 未合入的实验分支

| 分支 | 内容 | 状态 |
|---|---|---|
| `fix/cart-cache-invalidate-on-order` | 购物车缓存失效修复（`895b976`） | 待定是否合入 |

> `feature/mcp-experiment`（实验四 Spring AI 进程内 MCP Server，`32fd2b5`）**已于 2026-10-08 删除**，
> Go 工具链（271MB）、Go 缓存（154MB）、mcp-link 产物（15MB）一并清理。
> 该分支**从未合入 main**，删除不影响 main（仍在 `27f5dd1`）。

## 已知未修的缺陷（按优先级）

1. **Excel 异步任务框架的 14 条盲区**（详见 `2026-10-03.md`），高危四条：
   - ~~`params` 序列化失败会**静默导出全量**~~ ✅ **已修（2026-10-08）**：
     `writeParams` 改为抛 `EXCEL_TASK_PARAMS_INVALID(47)`，失败时任务行不落库。
     新增 `ExcelTaskServiceImplTest` 钉住；全 reactor 101 用例绿。
   - **导入重跑**：⚠️ 原描述「商品导入重传会再建一套 SPU+SKU」**不准确**，
     重传**同一份**文件是「整批全红、不落库」（数据安全），
     真正的重复风险是「**同名 + 换 SKU 编码**」（`product.name` 无唯一键）。
     两个子问题都**待用户定产品语义**，勿单方面改。
   - **表头不校验按列下标取值会串列**。
   - **导出用 OFFSET 分页会重复/漏行**。
   修复顺序：~~2~~ → 1（待定语义）→ 4 → 3 → 8。
2. **错误分页参数名被静默忽略**：`GET /api/products?pageNo=3&pageSize=2` 返回
   `current=1, size=10` 且 HTTP 200，没有任何报错信号。
