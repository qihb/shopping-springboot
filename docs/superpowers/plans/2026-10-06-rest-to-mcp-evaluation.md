# REST→MCP 四方案本机实测计划

---

## ⛔ 最终结论（2026-10-08）：**不采用 MCP，实验终止，产物已清理**

**决策**：REST→MCP 这条路**不采用**。

**理由**：

1. **可落地的方案依赖 Go，与技术栈不符**。实验二（mcp-link）是四家里唯一「零代码 + 真 HTTP 链路」
   都成立的方案，但它是个 **Go 写的旁路进程**。本项目是 Java / Spring Boot 单体 + Maven 多模块，
   引入 Go 运行时意味着多一套团队不维护、且与现有 CI / 部署链路完全无关的构建与运维负担。
2. **唯一同栈的方案有结构性缺陷**。实验四（Spring AI 进程内）是 Java 的，但不经 HTTP 过滤链
   → 拿不到 JWT 里的用户身份 → **写操作（加购/下单/支付）根本做不了**，只能暴露只读工具；
   且 `/sse` 端点无鉴权。这是设计限制，不是配置问题。
3. 综合：**收益（把 REST 接口变成 MCP 工具）不足以抵消「多一套异构运行时」或「功能被砍到只剩只读」的代价。**

**已执行的清理（2026-10-08 全部完成）**：

| 清理项 | 内容 | 大小 |
|---|---|---|
| Go 工具链 | 删 `~/.workbuddy-ai/binaries/go`（go1.27.1） | 271 MB |
| Go 缓存（**副作用路径**） | 删 `~/go`、`~/Library/Caches/go-build` | 154 MB |
| mcp-link | 删 `/tmp/mcp-link`、`/tmp/mcp-link.log`、`/tmp/mcpcfg` | 15 MB |
| 实验临时文件 | 删 `/tmp/mcp_api_docs.json`、`/tmp/mcp_login.json`、`/tmp/mcptest.token`、`/tmp/mcp_probe.py`、`/tmp/exp2.py`、`/tmp/mkurl.py`、`/tmp/check_spec.py` | — |
| 代码分支 | 删 `feature/mcp-experiment`（原提交 `32fd2b5`，仅本地，未合入 main） | — |
| MCP 评估技能 | 删 `.workbuddy-ai/skills/spring-shop-mcp-eval/`（SKILL.md + 2 个 reference + SSE 探针脚本） | 28 KB |

> ⚠️ **下文 Task 4 里提到的 `spring-shop-web/src/main/java/com/springshop/web/mcp/ProductMcpTools.java`、
> `McpToolConfig.java` 等文件现在都不存在**（随分支删除），本文档中出现的这些路径仅为历史记录，
> 不要在代码库里找它们。已核实 `target/spring-shop-web-1.0.0.jar` 里也没有 `web/mcp/` 类。

> **更正**：Task 2 里那条「残留清理：`pkill -f "mcp-link serve"` + `rm -rf /tmp/mcp-link`」的 checkbox
> 当时勾成了「已完成」，但复核发现**进程停了、目录没删**。上述清理是 2026-10-08 真正补做的，
> 那条记录与事实的偏差至此已经消除。

**刻意保留、未清理的**：

- `fix/cart-cache-invalidate-on-order`（`895b976`）—— 实验二写链路联调**当场暴露的后端真 bug**
  （下单后购物车 Redis 缓存不失效）的修复。**与 MCP 无关，是有价值的独立成果，不要一起删。**
- dev 库里的实验数据（订单 `20261008151828271005442`、支付 240.15、库存 362→359、
  `mcptest` 账号与收货地址）—— 用户决定先保留。

**本文件不删除**：下面的四家方案实测数据与踩坑记录，作为「**已评估并否决**」的存档保留，
避免以后重复调研。**不要按本文件继续执行 Task。**

---

> **For agentic workers:** ⛔ **本计划已于 2026-10-08 终止，请勿执行任何 Task。**
> 原文的 REQUIRED SUB-SKILL 指引（`superpowers:executing-plans`）**已失效**，仅作历史记录保留。
> 下方内容仅供查阅历史结论。Steps 用 checkbox (`- [ ]`) 语法追踪。

**Goal:** 在本机把 4 种「REST 接口转 MCP」方案各实践一遍，用统一度量表记录真实改造成本与效果，为团队化部署选型。

**Architecture:** 后端 spring_shop（6001 端口）保持不动；实验一用现成 OpenAPI 文档直连；实验二/三在本机起旁路进程把接口转成 MCP 工具；实验四是唯一改代码的方案（Spring AI 进程内 MCP Server），在独立分支进行。每个实验结束做残留清理，互不影响。

**Tech Stack:** Spring Boot 3.5.16 / springdoc（已集成）/ mcp-link（Go）/ ContextForge（Docker）/ Spring AI 1.1.4（兼容 Boot 3.5.x）/ MCP Inspector（验证工具）

---

## 统一度量表（每个实验做完填一列）

| 度量项 | 实验一 | 实验二 | 实验三 | 实验四 |
|---|---|---|---|---|
| 代码改动（文件数/行数） | 0 | **0** | | **5 个文件**：pom +1 依赖、`application-dev.yml` +8 行、`SecurityConfig` +3 行、新增 `ProductMcpTools`（92 行）、新增 `McpToolConfig`（30 行） |
| 新增部署物（进程/容器） | 0 | **1 个 Go 进程（8080）** | | **0**（与业务进程同生共死，不额外部署） |
| 配置/操作步骤数 | 3（拉文档→喂 AI→执行） | **4**（装 Go → 编译 → 起服务 → 拼 SSE 地址） | | **5**（加依赖 → 写工具类 → 注册 Bean → 放行白名单 → 重启） |
| 首个工具调通？障碍是什么 | ✅调通（curl 非工具）；障碍=分页参数需读 schema 才能拼对 | ✅**调通（真 MCP 工具：initialize → tools/list 拿到 29 个）**；障碍=① `h=` 必须传 **JSON**，README 写的 `header:value` 是错的 ② `f=` 必须用**分号**且要 `%3B` 编码（见踩坑） | | ✅**调通（3 个工具）**；障碍=**只给 Bean 加 `@Tool` 不会自动注册，工具数是 0**，必须显式提供一个 `ToolCallbackProvider` Bean（`@McpTool` 那套才由 annotation-scanner 自动扫） |
| 暴露工具数 / 描述质量（好/一般/差） | 0 个工具（仅文档）；60+ 接口 summary 全有（好），参数结构有误导（差）→ **2026-10-07 已修：分页参数已展开为 `current/size`，见下方更正** | **29 个工具**（`+/api/**;-/api/admin/**` 过滤掉全部 admin 接口，生效）；summary 中文描述**全有（好）**；但**写接口的 `requestBody` schema 是空对象（差，致命）** | | **3 个工具**（刻意只做只读）；**schema 质量四家里最好**：参数平铺（`categoryId/keyword/current/size` 四个独立参数）、每个带中文描述、`required: []` 正确、`additionalProperties: false`；**工具名就是方法名**（`searchProducts`），人可读 |
| 是否走真实 HTTP+JWT 链路 | 是（AI 生成的 curl 直接打后端，401/200 行为真实） | **是**（token 通过 `h=` 注入，每个工具调用都真打 6001；订单/支付/库存变化均已在 DB 与 Redis 侧核对） | | **否**。进程内直接调 Service，不经过 HTTP 过滤链 → **拿不到 JWT 里的用户身份**。这是结构性限制，不是配置问题 |
| 写链路（加购→下单→支付）能否走通 | —（不做） | **能走通，但靠人工补文档**：工具 schema 里 `requestBody` 为空，AI 无法自行拼出 `{"skuId":…,"quantity":…}`。链路 10 步全绿（下单号 `20261008151828271005442`、支付 240.15、库存 362→359） | | **走不通（设计上就不能）**：无身份传递，写操作需要 userId，进程内工具拿不到。要做只能额外设计（如把 token 作为工具参数传入 —— 那等于把鉴权交给模型，不可接受） |
| 同事接入需交付什么 | 文档地址 + token；但每次新对话要重新喂文档，无标准化接入方式 | **一条 SSE URL（内含 token）+ 放行 8080**；同事在 `mcpServers.url` 里填一次即可，无需重新喂文档 —— **比实验一明显标准化** | | 后端地址 `http://<host>:6001/sse`（需放行白名单）；**但完全没有鉴权** —— 任何能访问该端口的人都能调工具，团队化之前必须先补 |
| 残留要清理什么 | 无（仅 /tmp 临时文件） | **停 Go 进程 + 删 `/tmp/mcp-link`**（重启命令见文末） | | 切回 `main` + 重建 jar。**注意：`pkill -f "<jar 名>"` 会误伤用同一个 jar 起的其它实例，不要用**；分支 `feature/mcp-experiment` 保留待定 |
| 踩坑记录 | ① springdoc 把分页对象生成为单参数 `$ref`（`GET /api/products?query=…`），照文档拼参必错 → **已修**；② 错误分页参数被**静默忽略**（`pageNo/pageSize` 返回默认 10 条 + HTTP 200），无任何报错信号 → **仍在** | ① **`h=` 的格式 README 与实现不符**：实现要求 JSON 对象（`map[string]string`），README 写 `header:value`，照 README 拼必报 `invalid character 'A' looking for beginning of value`；② **`f=` 分隔符是分号不是逗号**，且 Go 的 `url.ParseQuery` 遇到裸 `;` 会**整条丢弃该参数**（过滤静默失效、admin 接口会漏出去），必须编码成 `%3B`；③ **写接口 `requestBody` schema 被丢成空对象**（OpenAPI 里定义完整，是 mcp-link 的缺陷）；④ 工具名是自动生成的长 slug（`mcplink_spring_shop_api_post_api_cart_items`），人不可读；⑤ **写链路联调当场暴露一个后端真 bug**（购物车缓存不一致，见下） | | ① **`@Tool` 不等于自动注册**：必须提供 `ToolCallbackProvider` Bean，否则 `tools/list` 返回 0 个工具且**不报任何错**；② **MCP 响应不走项目的 Jackson 配置**：`LocalDateTime` 被序列化成数组 `[2026,10,2,9,42,13]`，而同进程的 HTTP 接口返回 `"2026-10-02 09:42:13"`（已做 A/B 对照，见下）；③ 进程内无身份上下文 → 只能暴露只读工具；④ `mvn -pl spring-shop-web -am spring-boot:run` 会在**聚合 POM** 上也执行 `spring-boot:run` 并报 `Unable to find a suitable main class`，要先 `package` 再 `java -jar` |

### 实验一结论的两处更正（2026-10-08 复核）

计划写在 2026-10-06，之后 10-07 的 OpenAPI 改造（`a95f6b9`）改变了实验一的部分结论：

1. **踩坑① 已消失**：`GET /api/products` 现在展开为 5 个独立参数（`categoryId/keyword/status/current/size`），
   每个都带中文 `description`。原记录「springdoc 把分页对象折叠成单个 `$ref`，照文档拼参必错」**不再成立**。
2. **踩坑② 仍在**：实测 `?pageNo=3&pageSize=2` 返回 `current=1, size=10, records=10` 且 HTTP 200 ——
   参数名写错被**静默忽略**。对 MCP 场景这条更危险：AI 拼错参数名会以为「查到了 10 条」。

### 实验二结果（2026-10-08 实跑）

**接入方式**（唯一正确形式，注意 `h` 是 JSON、`f` 用 `%3B`）：

```
http://localhost:8080/sse?s=http://localhost:6001/v3/api-docs&u=http://localhost:6001
  &h={"Authorization":"Bearer <TOKEN>"}
  &f=%2B%2Fapi%2F%2A%2A%3B-%2Fapi%2Fadmin%2F%2A%2A
```

**写链路 10 步实测**（全部真实落库，已在 MySQL + Redis 侧核对）：

| 步骤 | 工具 | 结果 |
|---|---|---|
| ① 查商品列表 | `get_api_products` | ✅ 60 个商品，`searchParams` 里字段描述完整 |
| ② 查详情取 skuId | `get_api_products_id` | ✅ skuId=64，库存 362 |
| ③ 加购 | `post_api_cart_items` | ✅ 200 |
| ④ 查购物车 | `get_api_cart` | ✅ 条目已勾选 |
| ⑤ 建地址（**故意错手机号**） | `post_api_addresses` | ✅ 返回 `{"code":400,"message":"收货人手机号格式不正确"}` —— **HTTP 200 + 业务码**，符合项目约定 |
| ⑥ 建地址（正确） | `post_api_addresses` | ✅ addressId=5 |
| ⑦ 下单 | `post_api_orders` | ✅ orderNo=`20261008151828271005442` |
| ⑧ 模拟支付 | `post_api_pay_orderno_mockpay` | ✅ amount=240.15 |
| ⑨ 复查购物车 | `get_api_cart` | ⚠️ **仍显示该条目**（见下方 bug） |
| ⑩ 复查库存 | `get_api_products_id` | ✅ 362 → 359，销量 5423 → 5426 |

**⚠️ 写链路当场暴露的后端真 bug（高，未修）：下单后购物车缓存不失效**

- 现象：下单成功、库存已扣、DB 里 `cart_item` 该行**已物理删除**，但 `GET /api/cart` 仍返回该条目且 `checked=true`。
- 证据：`SELECT * FROM cart_item WHERE user_id=5` → 空；`redis-cli HGETALL cart:5` → `64 → 4|3|1`（cartItemId|quantity|checked）。
- 根因：`OrderServiceImpl.create()`（约 140 行）**直接用 `cartItemMapper.delete(...)` 删购物车行，绕过了
  `CartServiceImpl`**，因此只失效了商品详情缓存（`evictProductDetailCache`），**从未失效 `RedisKeys.cart(userId)`**。
  而 `CartServiceImpl` 自己的删除路径（`delete/deleteChecked/clear`）都是会失效缓存的。
- 影响：TTL 是 **7 天滑动过期**，用户只要继续操作就不断续期，脏数据可能长期不自愈；前端表现为
  「下单后购物车没清空、还是勾选状态」，再点结算才会报「购物车没有勾选商品」，属误导性 UX。
- 结论：**只读链路测不出这类问题** —— 这正是计划里判断「写链路才是方案选型的核心依据」的实证。


---

### 实验四结果（2026-10-08 实跑）

**分支**：`feature/mcp-experiment`（已提交 `32fd2b5`，**未合入 main**）
**改造文件**：`spring-shop-web/pom.xml`、`application-dev.yml`、`SecurityConfig`、
新增 `web/mcp/ProductMcpTools.java` + `web/mcp/McpToolConfig.java`

**结果：跑通了，而且 schema 质量是四家里最好的。**

```
[tools/list] 共 3 个工具
  - listCategoryTree   查询全部启用的商品分类树…                        params: []
  - searchProducts     分页查询前台在售商品列表（只返回上架商品）…      params: [categoryId, keyword, current, size]
  - getProductDetail   按商品 ID 查询前台在售商品详情…                  params: [id]
```

`searchProducts` 的完整入参 schema（对照实验二的空 `requestBody`）：

```json
{
  "type": "object",
  "properties": {
    "categoryId": { "type": "integer", "description": "分类 ID，不传表示不限分类。可先调用 listCategoryTree 获取" },
    "keyword":    { "type": "string",  "description": "商品名称关键字，模糊匹配，不传表示不筛选" },
    "current":    { "type": "integer", "description": "页码，从 1 开始，默认 1" },
    "size":       { "type": "integer", "description": "每页条数，默认 10，最大 100" }
  },
  "required": [],
  "additionalProperties": false
}
```

三个工具均真实调用成功（`listCategoryTree` 返回嵌套分类树；`searchProducts` 返回
`total=60, pages=30, current=1, size=2`，分页正确；`getProductDetail id=60` 返回 skuId=64、
库存 359 —— 与实验二下单后的扣减结果一致）。

**回归测试**：`mvn -pl spring-shop-web -am test` → **BUILD SUCCESS，全部用例通过**
（web 模块 101 个，其余模块亦全绿），新依赖未影响既有测试。

**⚠️ 实验四发现的两个新问题（都已修/已记录）**

1. **`@Tool` 不会自动注册**（已修）：只给 Bean 加 `@Tool`，`tools/list` 返回 **0 个工具且不报任何错**。
   必须显式提供 `ToolCallbackProvider` Bean（本实验用 `MethodToolCallbackProvider.builder().toolObjects(...)`）。
   注意别和 MCP 专用的 `@McpTool` 混淆 —— 那个才由 `spring.ai.mcp.server.annotation-scanner.enabled`（默认 true）自动扫。
   > 这类「静默返回空列表」的坑，如果没有探针去 `tools/list` 校验，很容易以为「配置好了」。
2. **MCP 响应不走项目的 Jackson 配置**（未修，属 Spring AI 行为）：同一个进程、同一份数据，两个通道序列化不同 ——

   | 通道 | `createTime` |
   |---|---|
   | HTTP `GET /api/products` | `"2026-10-02 09:42:13"` |
   | MCP 工具 `searchProducts` | `[2026, 10, 2, 9, 42, 13]` |

   `spring-shop-common` 的 `JacksonConfig` 里配了 `LocalDateTimeSerializer("yyyy-MM-dd HH:mm:ss")`，
   但 Spring AI 用自己内部的 ObjectMapper 序列化工具返回值，拿不到这个配置。
   **影响**：AI 客户端看到的是数组，需要自己知道这是「年月日时分秒」，与 REST 接口的口径不一致。
   修法：给 MCP 单独配 ObjectMapper（`McpJsonMapper`），或让工具返回已经格式化成字符串的 VO。

**⚠️ 结构性限制：进程内方案做不了写链路**

进程内工具直接调 Service，**不经过 HTTP 过滤链**，因此拿不到 JWT 里的用户身份。
写操作（加购/下单）都需要 `userId`，进程内工具无法安全获取 —— 把 token 当工具参数传等于把鉴权交给模型，不可接受。
所以实验四**只能暴露只读工具**。这与实验二形成互补：mcp-link 靠注入 `Authorization` 头能走写链路，
Spring AI 进程内靠源码拿精度但走不了写链路。

---

### Task 0: 统一前置准备（一次）

- [x] **启动应用**（dev profile，需本机 MySQL + Redis）：

```bash
mvn -pl spring-shop-web -am spring-boot:run
```

- [x] 验证健康与文档：`curl http://localhost:6001/api/health` 应返回 `ok`；`curl http://localhost:6001/v3/api-docs | head -c 300` 应返回 JSON
- [x] 准备测试账号（只做一次）：

```bash
curl -X POST http://localhost:6001/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"mcptest","password":"Test123456"}'
curl -X POST http://localhost:6001/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"mcptest","password":"Test123456"}'
```

记录响应里 `data.token`（后续写作 `$TOKEN`）。带鉴权的接口示例：`/api/cart/**`（需 `Authorization: Bearer $TOKEN`）。

- [x] 安装验证工具：**改用自写的 SSE + JSON-RPC 探针**（`/tmp/exp2.py`），未装 Inspector GUI。
  原因：沙箱会杀掉 SSE 长连接，且 GUI 不利于留可复现证据；探针能脚本化跑完写链路。
- [x] 无需 git 分支（本 Task 无代码改动）
- [x] 环境复核（2026-10-08）：后端 6001 ✅、MySQL 3306 ✅、Redis 6379 ✅、`mcptest` 可登录 ✅、
  `api-docs` 63 个接口 ✅、局域网 IP 仍为 `192.168.111.118` ✅

### Task 1: 实验一 OpenAPI 直连（基线，零部署）

- [x] 在 AI IDE 新开对话，把 `http://localhost:6001/v3/api-docs` 给 AI，要求：总结商品模块接口清单；生成「查商品列表 → 查详情」的 curl（含分页参数）
- [x] 让 AI 用 `$TOKEN` 生成一个购物车接口的调用并真实执行
- [x] 验收：AI 产出的请求参数正确、能实际调通（列表 `current/size` 生效、详情返回 SKU、购物车 401/200 行为正确）
- [x] 填度量表（预期：全部成本为 0；局限是只能「读文档」不能标准化调用，每次新对话要重新喂文档）

### Task 2: 实验二 mcp-link 本机旁路（零代码，真实 HTTP 链路）— ✅ 已完成 2026-10-08

> **前置踩坑：本机 Homebrew 装不了东西。** `/opt/homebrew/Library/Taps` 不存在，`brew install go`
> 要 clone `homebrew/core` 走 `github.com`，而本机 `github.com` 直连超时（`api.github.com` 却通），
> 实测**卡 9 分钟、下载缓存零新增**。改为从国内镜像取官方 Go 工具链。

- [x] 前置：**未用 `brew install go`**，改为
  `https://mirrors.aliyun.com/golang/go1.27.1.darwin-arm64.tar.gz` → 解压到
  `~/.workbuddy-ai/binaries/go/`（9.5 秒下完）。调用需带 `GOPROXY=https://goproxy.cn,direct`
- [x] 安装并启动（编译 7.6 秒，产物 14MB）：

```bash
git clone --depth 1 https://github.com/automation-ai-labs/mcp-link.git /tmp/mcp-link
cd /tmp/mcp-link
GOROOT=$HOME/.workbuddy-ai/binaries/go PATH=$HOME/.workbuddy-ai/binaries/go/bin:$PATH \
  GOPROXY=https://goproxy.cn,direct GOSUMDB=off go build -o mcp-link .
./mcp-link serve --port 8080 --host 0.0.0.0
```

- [x] 组装 SSE 接入地址 —— **计划里原来给的地址是错的，两处都错**（`h` 要 JSON、`f` 要分号且需 `%3B` 编码）：

```
http://localhost:8080/sse?s=http://localhost:6001/v3/api-docs&u=http://localhost:6001
  &h={"Authorization":"Bearer <TOKEN>"}
  &f=%2B%2Fapi%2F%2A%2A%3B-%2Fapi%2Fadmin%2F%2A%2A
```

- [x] 验收工具列表：**29 个工具，无任何 admin 接口**（`f=` 过滤生效）；中文描述全部来自 OpenAPI 的
  `@Operation(summary)`。改用自写探针 `/tmp/exp2.py`（`list` / `schema` / `chain` / `stop`）替代 Inspector
- [x] **写链路联调（真实度核心检验）**：10 步全绿 —— 查商品 → 取 skuId → 加购 → 查购物车 →
  建地址（**先故意传错手机号**）→ 建地址 → 下单 → 模拟支付 → 复查购物车 → 复查库存。
  结果见上文《实验二结果》表：orderNo `20261008151828271005442`、支付 240.15、库存 362→359
- [x] 写链路验收：AI 能把上一接口返回值作为下一接口入参（skuId → addressId → orderNo）✅；
  中途失败能看到真实业务错误 ✅（错手机号返回 `{"code":400,"message":"收货人手机号格式不正确"}`）；
  **但工具 schema 里 `requestBody` 是空对象，AI 无法自行拼出写接口入参，必须人工补文档** ❌
- [x] **写链路暴露后端真 bug**：下单后购物车 Redis 缓存不失效（详见上文，高优先级待修）
- [ ] 局域网联调验收：**未做** —— 需把地址里 `localhost` 换成 `192.168.111.118` 并放行 8080 防火墙，
  属需要真人在另一台机器上操作的步骤，留给用户
- [x] 填度量表。残留清理：`pkill -f "mcp-link serve"` + `rm -rf /tmp/mcp-link`。
  注意：token 2 小时过期，过期后需重新生成 `h=` 参数并重连

### Task 3: 实验三 ContextForge Docker（零代码，带审计 UI）— ⛔ 阻塞：本机没有 Docker

> **2026-10-08 核查：本机未安装任何容器运行时** —— 无 `/Applications/Docker.app`，无 OrbStack / Rancher，
> 也没有 `docker` CLI（`/opt/homebrew/bin/docker` 与 `/usr/local/bin/docker` 都不存在）。
> 且 Homebrew 当前装不了东西（见 Task 2 前置踩坑），`brew install --cask docker` 同样走不通。
> **需要用户决策**：① 装 Docker Desktop（几百 MB，需 GUI 授权，商用场景注意许可）；
> ② 用 `colima + docker`（无 GUI，但同样要先修好 Homebrew）；③ 跳过实验三，用实验二 + 实验四做对比。

- [ ] 启动（macOS Docker Desktop，容器内用 `host.docker.internal` 访问宿主机）：

```bash
docker run -d --name mcpgateway -p 4444:4444 -e HOST=0.0.0.0 \
  -e JWT_SECRET_KEY=local-dev-secret-key-32bytes-abcdefg \
  -e MCPGATEWAY_UI_ENABLED=true -e MCPGATEWAY_ADMIN_API_ENABLED=true \
  -e PLATFORM_ADMIN_EMAIL=admin@example.com -e PLATFORM_ADMIN_PASSWORD=changeme \
  ghcr.io/ibm/mcp-context-forge:1.0.0-RC-2
```

- [ ] 浏览器开 `http://localhost:4444` 用上面邮箱/密码登录 Admin UI
- [ ] 在 UI 里把 spring_shop 注册为 REST 服务：文档地址填 `http://host.docker.internal:6001/v3/api-docs`，基础地址 `http://host.docker.internal:6001`，出站认证选 Bearer 填 `$TOKEN`（界面入口名称以当前版本为准：Tools/Virtual Servers 相关表单）
- [ ] 生成网关访问 token：

```bash
docker exec mcpgateway python3 -m mcpgateway.utils.create_jwt_token \
  --username admin@example.com --exp 10080 --secret local-dev-secret-key-32bytes-abcdefg
```

- [ ] Inspector 连 `http://localhost:4444/mcp`，请求头 `Authorization: Bearer <上一步token>`，验收：工具可见、调用「商品详情」成功、Admin UI 日志页能看到这次调用记录（联调证据链）
- [ ] 写链路联调：按实验二同一条链路（加购 → 建地址 → 下单 → 支付 → 复查购物车清空/库存扣减）走一遍，重点看 Admin UI 日志页是否把**每一次写调用**（调用者、工具名、耗时）都记成证据链——这是实验三相对实验二的差异化价值
- [ ] 填度量表。残留清理：`docker rm -f mcpgateway`

### Task 4: 实验四 Spring AI 1.1.4 进程内（唯一改代码，在分支做）— ✅ 已完成 2026-10-08

**Files:**
- Modify: `spring-shop-web/pom.xml`（加依赖）
- Modify: `spring-shop-web/src/main/resources/application-dev.yml`（加 mcp server 配置）
- Modify: `spring-shop-web/src/main/java/com/springshop/web/security/SecurityConfig.java:95`（白名单放行）
- Create: `spring-shop-web/src/main/java/com/springshop/web/mcp/ProductMcpTools.java`

- [ ] 建分支：`git checkout -b feature/mcp-experiment`
- [ ] `spring-shop-web/pom.xml` 增加依赖（版本号 1.1.4 已确认兼容 Boot 3.5.x；正式落地时再挪到父 POM 的 dependencyManagement）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    <version>1.1.4</version>
</dependency>
```

- [x] `application-dev.yml` 追加（**只放 dev**：MCP 端点当前是实验期 `permitAll`，绝不能带到 prod）：

```yaml
spring:
  ai:
    mcp:
      server:
        name: spring-shop-mcp
        version: 1.0.0
        protocol: SSE
        type: SYNC
```

- [x] 新建工具类（复用现有 Service，**实测做了 3 个只读工具** —— 比原计划多一个
  `searchProducts`，因为要验证「带筛选参数的工具 schema 是否平铺且带描述」，这正是对照实验二的关键）：

```java
@Service
public class ProductMcpTools {
    private final ProductQueryService productQueryService;
    private final CategoryService categoryService;

    @Tool(description = "按商品 ID 查询前台在售商品详情，返回…以及该商品的全部 SKU")
    public ProductDetailVO getProductDetail(
            @ToolParam(description = "商品 ID（SPU 主键，正整数…）") Long id) { … }

    @Tool(description = "查询全部启用的商品分类树…")
    public List<CategoryNodeVO> listCategoryTree() { return categoryService.tree(); }

    @Tool(description = "分页查询前台在售商品列表（只返回上架商品）…")
    public PageResult<ProductListVO> searchProducts(
            @ToolParam(description = "分类 ID，不传表示不限分类…", required = false) Long categoryId,
            @ToolParam(description = "商品名称关键字，模糊匹配…", required = false) String keyword,
            @ToolParam(description = "页码，从 1 开始，默认 1", required = false) Long current,
            @ToolParam(description = "每页条数，默认 10，最大 100", required = false) Long size) { … }
}
```

- [x] **必须额外新建 `McpToolConfig`（原计划漏了这一步，是本次最大的坑）**：
  只加 `@Tool` 不会注册，`tools/list` 返回 0 个工具且不报错。要显式给一个 `ToolCallbackProvider` Bean：

```java
@Bean
public ToolCallbackProvider productToolCallbackProvider(ProductMcpTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
}
```

import：`org.springframework.ai.tool.annotation.Tool` / `ToolParam`（在 `spring-ai-model` jar 里）、
`org.springframework.ai.tool.ToolCallbackProvider`、`org.springframework.ai.tool.method.MethodToolCallbackProvider`、
`com.springshop.product.product.service.ProductQueryService`、`com.springshop.product.category.service.CategoryService`

- [x] `SecurityConfig#appSecurityFilterChain` 白名单追加（**实验期放行，生产必须收回或改为要求 Bearer token**）：

```java
.requestMatchers("/sse", "/sse/**", "/mcp", "/mcp/**").permitAll()
```

- [x] 重启应用验收：用探针连 `http://localhost:6002/sse`（**没有动同事在 6001 上的实例，
  实验实例单独跑在 6002**），`tools/list` 拿到 3 个工具、schema 精准、三个工具均真实调用成功
- [x] 回归验证：`mvn -pl spring-shop-web -am test` → **BUILD SUCCESS，全部用例通过**，新依赖无副作用
- [x] 填度量表。**残留处理（与原计划不同，故意不删分支）**：`feature/mcp-experiment` 已提交 `32fd2b5`
  但**保留未删** —— 方案效果不错，是否保留/合入需要你决定，不能替你删掉。
  已切回 `main` 并重建了干净的 jar（`target/spring-shop-web-1.0.0.jar` 里确认无 `web/mcp/` 类）

### Task 5: 实验五 api2mcp4j 兼容性核对（只验证不改造）— ⚠️ 前提已不成立

- [x] ~~项目内已有源码副本 `.api2mcp4j-src/`~~ —— **2026-10-08 核查：该目录不存在**，
  且 `git status` 干净说明它从未提交或已被清理。计划里的这条前提已失效，需要重新 clone 才能核对。
- [x] 记录结论（沿用原判断）：该 Starter 声明依赖 Spring Boot 4.1.0 + Spring AI 2.0.0，
  本项目 Boot 3.5.16，**需先升级 Spring Boot 才能用** —— 只把「升级成本」记为成本项，不做依赖冲突实测
- [x] 确认本项目文件零改动（`git status` 干净）

### Task 6: 汇总记录与决策 — 🔶 部分完成（实验三阻塞，暂缺一列）

- [x] **汇总三张度量表（实验一/二/四；实验三待 Docker）为对比结论**：

  | 维度 | 实验一 直连 | 实验二 mcp-link | 实验四 Spring AI 进程内 |
  |---|---|---|---|
  | 代码改动 | 0 | 0 | **5 个文件** |
  | 额外部署物 | 0 | 1 个 Go 进程 | **0** |
  | 工具描述精度 | 只有文档 | 中文描述好，**写接口 schema 空** | **最好**（平铺+描述+required 正确） |
  | 工具名可读性 | — | 长 slug，不可读 | **就是方法名** |
  | 写链路 | 做不了 | **能走通**（靠注入 Authorization 头） | **做不了**（无身份上下文） |
  | 同事接入 | 每次重喂文档 | 一条 URL | 一条 URL，但**无鉴权** |
  | 与代码同仓库演进 | — | 外挂，接口变了要重扫 | **同仓库、随代码演进** |

  **一句话结论：实验二和实验四不是替代关系，是互补的 ——
  实验二能写不能保证 schema 精度，实验四 schema 最准但写不了。**
- [x] **写链路表现纳入对比权重（已被实证）**：只读链路三家都容易通，差异确实全在写链路 ——
  实验二的空 `requestBody`、实验四的身份缺失，都是跑写链路才暴露的。
  另外实验二的写链路联调**当场揪出一个后端真 bug**（购物车缓存不一致），这是纯只读验证绝对拿不到的收益。
- [ ] 服务器化路径：**未做**，等实验三补齐、方案定下来再谈
- [ ] **实验三（ContextForge）待决策**：本机没有 Docker，需要你定「装 Docker Desktop / 用 colima / 跳过」

