---
name: spring-shop-excel-task
description: 在 spring-shop 仓库中新增、修改或排查 Excel 异步导入导出功能时使用。覆盖公共框架（ExcelSupport / ExcelStreamWriter / ExcelExportSupport / ExcelTask*）的用法与边界、商品与管理员导入的分组与落库重试语义、四类导出的接入步骤、excel_task 任务表与任务中心接口、excel.task.* 配置项，以及异步集成测试的硬约束。也用于排查「导入计数一直是 0」「任务卡在 RUNNING」「导出文件只有表头」「Fesod 读到 0 行」「表头被转置」这类具体问题。
agent_created: true
---

# spring-shop Excel 异步导入导出

## 这个技能解决什么

本仓库的导入导出**全部是异步任务**：HTTP 请求只校验文件、落一条任务记录、返回任务号；
真正的解析与落库跑在独立的 `excel-task` 线程池里；前端轮询进度，最后下载结果文件。

这不是为了「架构好看」，而是三个具体约束逼出来的：请求不能超时、内存不能随文件大小增长、
部分失败必须能定位到行。

`AGENTS.md` 的「异步导入导出（Excel 任务框架，强约束）」是**规范来源**（12 条硬约束）；
本技能是**可执行的操作手册** —— 告诉你文件在哪、怎么加一个新的、以及会踩什么坑。

## 什么时候用

- 要新增一个导入或导出接口（比如给优惠券、分类加导出）。
- 要改动现有导入/导出的解析、校验、落库或进度语义。
- 排查：导入 `successRows`/`failRows` 一直是 0、任务卡在 `RUNNING`、导出文件打不开、
  导入整批报「分类不存在」、Fesod 读出来 0 行。
- 调整 `excel.task.*` 配置或任务表结构。

## 代码地图

### 公共框架（`spring-shop-common`，**不含业务逻辑**）

| 文件 | 职责 |
|---|---|
| `common/excel/ExcelSupport` | 流式读（`streamRead` / `readAll`）、动态写（`writeDynamic`）、单元格解析（`parseDecimal` / `parseInt` / `isBlankText`） |
| `common/excel/ExcelReadOptions` | 读取参数：`maxColumns=64`、`maxRows`、`sheetNo`、`headRowNumber`、`fileType` |
| `common/excel/ExcelFileType` | 由文件名判定 XLSX/XLS |
| `common/excel/ExcelStreamWriter` | 分批写；`toColumnMajorHead` 修表头转置；空集合也走一遍 `write` 保住表头 |
| `common/excel/ExcelExportSupport` | `PageFetcher` + 分页导出主循环，**导出只需接这里** |
| `common/excel/ExcelRow` / `ImportError` / `ExcelReadException` | 行模型与错误模型 |
| `common/excel/task/ExcelTaskExecutor` | 受理 + 调度 + 状态兜底（`submitImport` / `submitExport`） |
| `common/excel/task/ExcelTaskService(+impl)` | 任务台账读写、失败明细存取、文件清理。**不做调度**（避免依赖环） |
| `common/excel/task/ExcelTaskContext` | 交给 worker 的上下文：源文件、输出文件、条件还原、进度上报、错误收集 |
| `common/excel/task/ExcelTaskWorker` | worker 函数式接口 |
| `common/excel/task/ExcelTaskProperties` | `excel.task.*` 配置绑定 |
| `common/excel/task/ExcelTaskConfig` | 线程池 bean，名为 **`excelTaskThreadPool`**（不能叫 `excelTaskExecutor`，会与调度器的 bean 名撞车） |
| `common/excel/task/ExcelFileStorage` | 临时文件 `{tmpDir}/{yyyyMMdd}/{taskNo}.{ext}` |
| `common/excel/task/ExcelTaskCleanupTask` | 过期文件清理 |
| `common/excel/task/mapper/` | 两个 Mapper。**必须在 `*.mapper` 包下**（见下方装配陷阱） |
| `spring-shop-web/.../db/migration/V8__excel_task.sql` | 任务表 DDL（`excel_task` + `excel_task_error`） |
| `spring-shop-web/.../controller/ExcelTaskController` | 任务中心：列表 / 详情 / 下载 |

### 业务侧

| 业务 | 导入 | 导出 |
|---|---|---|
| 商品 | `product/service/impl/ProductImportServiceImpl` | `product/service/impl/ProductExportServiceImpl` + `vo/ProductExportRow` |
| 管理员 | `admin/service/impl/AdminUserImportServiceImpl` | `admin/service/impl/AdminUserExportServiceImpl` + `vo/AdminUserExportRow` |
| 操作日志 | — | `admin/service/impl/OperationLogExportServiceImpl` + `vo/OperationLogExportRow` |
| 订单 | — | `order/service/impl/OrderExportServiceImpl` + `vo/OrderExportRow` |

## 接口清单

| 方法 | 路径 | 权限 |
|---|---|---|
| POST | `/api/admin/products/import` | `product:product:import` |
| GET | `/api/admin/products/import/template` | `product:product:import` |
| POST | `/api/admin/products/export` | `product:product:list` |
| POST | `/api/admin/users/import` | `system:user:import` |
| GET | `/api/admin/users/import/template` | `system:user:import` |
| POST | `/api/admin/users/export` | `system:user:list` |
| POST | `/api/admin/operation-logs/export` | `system:log:list` |
| POST | `/api/admin/orders/export` | `order:order:list` |
| GET | `/api/admin/excel-tasks` | 仅需登录 |
| GET | `/api/admin/excel-tasks/{taskNo}` | 仅需登录 |
| GET | `/api/admin/excel-tasks/{taskNo}/download` | 仅需登录 |

三条接口约定，改动时不要破坏：

- **所有导出都是 `POST` + JSON body**，形状一致，前端共用一套调用代码。
  时间字段靠全局 `JacksonConfig` 解析（`yyyy-MM-dd HH:mm:ss`），
  所以查询 DTO 上**不需要** `@DateTimeFormat` —— 那是给表单/查询参数绑定用的。
- **导出沿用列表页的权限码**，不新增 `xxx:export`。能看列表就能导出同一份数据，
  另造权限码只会让菜单种子数据和角色授权多一层维护成本。
- 任务中心三个接口用 `@PreAuthorize("isAuthenticated()")`，**权限模型是「归属」而非权限码**：
  提交任务时已过权限码校验，任务本身靠 `created_by` 过滤，只能看自己的。
  查不到与他人任务统一返回「任务不存在」，不给探测任务号的机会。

## 新增一个导出：3 步

1. **DTO + VO** —— 仿 `OrderExportQuery`（筛选条件 + 可选 `ids`）和 `OrderExportRow`
   （一行一条，列名与列表页口径一致）。`ids` 优先于筛选条件。
2. **Service** —— 接口加 `submitExport(query, adminId)`，实现体直接委托给
   `ExcelExportSupport.export(context, QueryType.class, RowType.class, sheetName, fetcher)`，
   `fetcher` 里调用已有的列表查询（新增一个 `xxxExportPage(query, current, pageSize)`，
   用 `new Page<>(current, pageSize, false)` 关掉 count 查询）。
3. **Controller** —— `@PostMapping("/export")` + `@RequestBody`，返回 `Result<ExcelTaskVO>`，
   权限码复用列表的。

导出 0 行时**仍要产出只有表头的合法文件**（`ExcelExportSupport` 已处理）：
没有数据就直接不写文件的话，用户点下载会拿到损坏文件，分不清「确实没有」还是「导出坏了」。

## 新增一个导入：与导出的差异

- 受理阶段先 `validateFile(file)`（空文件、扩展名），**格式错误在受理阶段就报错**，
  不占用异步线程、也不产生垃圾任务。
- worker 里 `readRows` 要显式传 `ExcelFileType.fromFileName(context.getFileName())`。
- 落库用「批级事务 + 批内原子 + 批间独立」；整批失败后**逐组/逐行重试**，
  把唯一键冲突精确降级到某一行。用 `TransactionTemplate`，**不是 `@Transactional`**
  （worker 是异步线程里的自调用，不走代理）。
- 失败行占用的唯一编码（如 SKU 编码）在落库失败后**必须释放**，
  否则后续同编码的合法行会被误判为重复。
- 进度用**局部 recorder 对象**计数，**不能**用 Service 字段（单例，两个任务并发会互相污染）。
- 计数器要在**落库之后**上报，并在末尾**无条件 flush 一次**。
- `UserContext` 是请求级 `ThreadLocal`，**不跨线程** —— 管理员 id 必须在受理时就捕获并写进任务表。

## 装配陷阱（改包名/加类时最容易撞）

- **bean 名撞车**：`@Component ExcelTaskExecutor` 推导出的 bean 名就是 `excelTaskExecutor`，
  线程池 bean 不能同名，否则 `BeanDefinitionOverrideException`。现在叫 `excelTaskThreadPool`。
- **`@MapperScan("com.springshop.**.mapper")` 会关掉 MyBatis 的 `@Mapper` 注解自动扫描** ——
  Mapper 接口必须落在以 `mapper` 结尾的包里，放在实体旁边会 `NoSuchBeanDefinitionException`。
- **包名与目录不一致**：文件在 `.../task/impl/` 却写 `package ...excel.task;`，
  编译产物落到 `task/`，残留旧 class 导致 `ConflictingBeanDefinitionException`。
  **改包名后必须 `mvn clean -pl <module>`**。
- 别单独声明 `poi-ooxml`：`fesod-sheet` 自带 POI，版本不一致会 `NoSuchMethodError`。

## Fesod 三个静默失败的坑

三个都不报错，只是「结果不对」，所以特别费时间。详细写法见
`references/fesod-pitfalls.md`。

| 症状 | 成因 |
|---|---|
| 表头被转置（3 行 × 1 列） | `head(List<List<String>>)` 是**列优先**的，传一个普通表头列表会被转置 |
| 损坏文件读成 0 行而不报错 | 自动探测文件类型会**静默退化成 CSV**，必须显式传 `excelType(...)` |
| 回读报 `Can not find any sheet!` | Fesod **零行不建 sheet**，空集合也要走一遍 `write` |

## 测试铁律

- **涉及异步任务的集成测试不得加 `@Transactional`**：worker 用另一条连接，
  测试事务对它不可见 —— 前置数据「查不到」、任务状态「永远未开始」。
- 测试数据带 `RUN_TAG` 唯一后缀（`System.nanoTime() % 1_000_000`），H2 在同一 surefire JVM 内共享。
- **按名称/编码断言，不依赖任何总数**。
- **轮询**等终态（50ms 一次、上限 30s），不要 sleep 固定时长。
- **看业务码，不只看 HTTP 状态**：业务失败也是 HTTP 200。
- **不要对 `Result.data` 直接 `asLong()`/`asText()`**：`data` 为 JSON `null` 时 Jackson 给的是
  `NullNode`（非 null 引用），`asLong()` **静默返回 0**。先断言 `code == 200` 且 `data` 非 null。
- **共享前置数据要幂等**：`POST /api/admin/categories` 返回 `Result<Void>` 拿不到 id，
  且分类表对名称没有唯一约束 —— 每个用例建一次同名分类会堆出多条，
  而导入按名称匹配分类时「同名多条宁可整组失败也不猜」，后跑的用例会莫名全灭。

参考现有实现：`ProductImportIntegrationTest`、`ExcelTaskIntegrationTest`、
`ProductImportMultipartIntegrationTest`（真实 Tomcat + 真实 HTTP 下载）。

## 排查速查

| 现象 | 先看哪里 |
|---|---|
| 计数全是 0 | 进度是否在落库之后上报；末尾是否无条件 flush |
| 任务卡在 RUNNING | worker 是否抛了未被捕获的异常；线程池是否被拒绝 |
| 导出文件打不开 | 是否写入了表头（0 行场景）；`finish()` 是否被调用 |
| 导入报「分类不存在」 | 库里是否有**多条同名分类** |
| 读出来 0 行 | 是否显式传了 `excelType` |
| 表头错乱 | `head()` 是否做了列优先转置 |

## Resources

- `references/fesod-pitfalls.md` —— Fesod 读写 API 的坑、正确写法、`javap` 探针命令、
  类型解析助手的口径。**写任何 Fesod 读写代码之前先读它。**
- `references/task-internals.md` —— 任务表设计理由、状态机、`excel.task.*` 逐项含义、
  worker 契约、两个线程亲和性陷阱。**改任务表结构或配置语义之前先读它。**
- `references/frontend-integration.md` —— 全部导入/导出接口清单（含权限码与入参形态）、
  `ExcelTaskVO` 字段用法、轮询的四个纪律、**下载的三个坑**（不能 `<a href>`、`filename*`
  解析、失败时 HTTP 200 但响应体是 JSON）、后端约束对照表。**前端对接或写前端联调文档之前先读它。**
