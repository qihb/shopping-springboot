# REST→MCP 四方案本机实测计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在本机把 4 种「REST 接口转 MCP」方案各实践一遍，用统一度量表记录真实改造成本与效果，为团队化部署选型。

**Architecture:** 后端 spring_shop（6001 端口）保持不动；实验一用现成 OpenAPI 文档直连；实验二/三在本机起旁路进程把接口转成 MCP 工具；实验四是唯一改代码的方案（Spring AI 进程内 MCP Server），在独立分支进行。每个实验结束做残留清理，互不影响。

**Tech Stack:** Spring Boot 3.5.16 / springdoc（已集成）/ mcp-link（Go）/ ContextForge（Docker）/ Spring AI 1.1.4（兼容 Boot 3.5.x）/ MCP Inspector（验证工具）

---

## 统一度量表（每个实验做完填一列）

| 度量项 | 实验一 | 实验二 | 实验三 | 实验四 |
|---|---|---|---|---|
| 代码改动（文件数/行数） | 0 | | | |
| 新增部署物（进程/容器） | 0 | | | |
| 配置/操作步骤数 | 3（拉文档→喂 AI→执行） | | | |
| 首个工具调通？障碍是什么 | ✅调通（curl 非工具）；障碍=分页参数需读 schema 才能拼对 | | | |
| 暴露工具数 / 描述质量（好/一般/差） | 0 个工具（仅文档）；60+ 接口 summary 全有（好），参数结构有误导（差） | | | |
| 是否走真实 HTTP+JWT 链路 | 是（AI 生成的 curl 直接打后端，401/200 行为真实） | | | |
| 写链路（加购→下单→支付）能否走通 | —（不做） | | | |
| 同事接入需交付什么 | 文档地址 + token；但每次新对话要重新喂文档，无标准化接入方式 | | | |
| 残留要清理什么 | 无（仅 /tmp 临时文件） | | | |
| 踩坑记录 | ① springdoc 把分页对象生成为单参数 `$ref`（`GET /api/products?query=…`），照文档拼参必错，须展开读 `ProductPageQuery` schema；② 错误分页参数被**静默忽略**（`pageNo/pageSize` 返回默认 10 条 + HTTP 200），无任何报错信号 | | | |

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

- [ ] 安装验证工具：`npx @modelcontextprotocol/inspector`（需 Node 18+）（实验二开始前再装即可）
- [x] 查本机局域网 IP：`ipconfig getifaddr en0`，记录为 `$LAN_IP`（实测 `192.168.111.118`）
- [ ] 无需 git 分支（本 Task 无代码改动）

### Task 1: 实验一 OpenAPI 直连（基线，零部署）

- [x] 在 AI IDE 新开对话，把 `http://localhost:6001/v3/api-docs` 给 AI，要求：总结商品模块接口清单；生成「查商品列表 → 查详情」的 curl（含分页参数）
- [x] 让 AI 用 `$TOKEN` 生成一个购物车接口的调用并真实执行
- [x] 验收：AI 产出的请求参数正确、能实际调通（列表 `current/size` 生效、详情返回 SKU、购物车 401/200 行为正确）
- [x] 填度量表（预期：全部成本为 0；局限是只能「读文档」不能标准化调用，每次新对话要重新喂文档）

### Task 2: 实验二 mcp-link 本机旁路（零代码，真实 HTTP 链路）

- [ ] 前置：`go version` 需 ≥1.21，没有则 `brew install go`
- [ ] 安装并启动：

```bash
git clone https://github.com/automation-ai-labs/mcp-link.git /tmp/mcp-link
cd /tmp/mcp-link && go run main.go serve --port 8080 --host 0.0.0.0
```

- [ ] 组装 SSE 接入地址（`s`=文档、`u`=后端、`h`=注入的鉴权头、`f`=接口过滤）：

```
http://localhost:8080/sse?s=http://localhost:6001/v3/api-docs&u=http://localhost:6001&h=Authorization:Bearer%20$TOKEN&f=+/api/**,-/api/admin/**
```

- [ ] 用 Inspector（Transport 选 SSE）粘贴该地址，验收：工具列表包含商品/购物车接口、无 admin 接口；调用「商品列表」「商品详情」「购物车查询」三个工具成功
- [ ] **写链路联调（真实度核心检验，只读链路测不出的问题都在这里暴露）**：让 AI 按链路连续调用——
  1. `GET /api/products` 挑一个在售商品 → `GET /api/products/{id}` 取 skuId
  2. `POST /api/cart/items` 加购，入参 `{"skuId":…,"quantity":1}`；`GET /api/cart` 确认条目已勾选（未勾选先调 `PUT /api/cart/items/{id}/checked`）
  3. `POST /api/addresses` 建收货地址（手机号必须 `1[3-9]` 开头 11 位；**故意传错一次**，观察参数校验失败返回的是业务码还是 HTTP 错误）
  4. `POST /api/orders` 下单，入参 `{"addressId":…}`，记录返回的 orderNo；可选 `POST /api/orders/{orderNo}/pay` 模拟支付（无入参）
  5. 复查：`GET /api/cart` 条目已被清空、`GET /api/products/{id}` 库存已扣减——证明写操作真实落库而非 AI 假装成功
- [ ] 写链路验收：AI 能把上一接口返回值作为下一接口入参（skuId → cartItemId → addressId → orderNo）；链路中途失败时能看到真实业务错误（如库存不足）而非静默成功
- [ ] 局域网联调验收：把地址里 `localhost` 换成 `$LAN_IP` 给同事，其 AI IDE 配 `mcpServers.url` 即接入；本机防火墙放行 8080
- [ ] 填度量表。残留清理：`Ctrl+C` 停进程、删 `/tmp/mcp-link`。注意：token 过期后改 `h=` 参数重连

### Task 3: 实验三 ContextForge Docker（零代码，带审计 UI）

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

### Task 4: 实验四 Spring AI 1.1.4 进程内（唯一改代码，在分支做）

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

- [ ] `application-dev.yml` 追加：

```yaml
spring:
  ai:
    mcp:
      server:
        name: spring-shop-mcp
        version: 1.0.0
```

- [ ] 新建工具类（复用现有 Service，只写 2 个只读工具；SSE 端点 `/sse`、`/mcp/message` 由 starter 自动提供）：

```java
@Service
public class ProductMcpTools {
    private final ProductQueryService productQueryService;
    private final CategoryService categoryService;

    @Tool(description = "按商品ID查商品详情，含 SKU 与图片")
    public ProductDetailVO getProductDetail(Long id) { return productQueryService.appDetail(id); }

    @Tool(description = "查询全部分类树")
    public List<CategoryNodeVO> listCategoryTree() { return categoryService.tree(); }
}
```

import：`org.springframework.ai.tool.annotation.Tool`、`com.springshop.product.product.service.ProductQueryService`、`com.springshop.product.category.service.CategoryService`

- [ ] `SecurityConfig#appSecurityFilterChain` 白名单追加一行（实验期放行，生产须收回）：

```java
.requestMatchers("/sse/**", "/mcp/**").permitAll()
```

- [ ] 重启应用，Inspector 连 `http://localhost:6001/sse`，验收：能看到 2 个工具且描述精确，调用成功
- [ ] 回归验证：`mvn -pl spring-shop-web -am test`（记录新依赖是否影响既有测试，踩坑如实记录）
- [ ] 填度量表。残留处理：`git checkout main && git branch -D feature/mcp-experiment`；若决定保留该方案，另行走 PR 评审合入

### Task 5: 实验五 api2mcp4j 兼容性核对（只验证不改造）

- [ ] 项目内已有源码副本 `.api2mcp4j-src/`，其 README/pom 声明依赖 Spring Boot 4.1.0 + Spring AI 2.0.0
- [ ] 记录结论：本项目 Boot 3.5.16，需升级 Spring Boot 才能用该 Starter——本次不做依赖冲突实测，只把「升级成本」作为该方案的成本项记录进度量表
- [ ] 确认本项目文件零改动（`git status` 应干净）

### Task 6: 汇总记录与决策

- [ ] 汇总四张度量表为最终对比结论，按以下规则选型：
  1. 只想快速让 AI 查接口 → 实验一/二
  2. 联调要真实 HTTP 链路 + 同事最快接入 → 实验二
  3. 团队化需要审计日志/按人管理 → 实验三（其配置可直接迁服务器）
  4. 长期演进、工具描述最精准、与代码同仓库维护 → 实验四
  5. 实验五结论作为「若将来升级 Boot」的备选项
- [ ] 写链路表现纳入对比权重：只读链路各家都容易通，真正的差异出在写链路——多接口参数衔接（skuId→cartItemId→addressId→orderNo）、参数校验错误是否可读、重复提交的表现，作为联调方案选型的核心依据
- [ ] 服务器化路径：选定方案后，把本机配置中的 `localhost`/`host.docker.internal` 换成服务器地址重新部署即可；团队化阶段的鉴权与工具白名单沿用实验二/三已验证的 `f=` 过滤与 Bearer 配置
