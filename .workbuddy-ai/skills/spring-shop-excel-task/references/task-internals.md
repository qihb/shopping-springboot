# 任务框架内部：表结构、状态机、配置、契约

改动任务表结构、状态语义或配置项含义之前读这一份。
DDL 本体在 `spring-shop-web/src/main/resources/db/migration/V8__excel_task.sql`
（测试库对应 `src/test/resources/db/migration-test/V8__excel_task.sql`，**两份要同步改**）。

---

## 两张表的分工

| 表 | 存什么 | 特点 |
|---|---|---|
| `excel_task` | 任务台账：业务类型、方向、状态、计数、文件路径、序列化查询条件、提交人 | 一行一个任务 |
| `excel_task_error` | 行级失败明细：`task_no` + `row_num` + `message` | 按 `task_no` 查，条数受 `max-error-rows` 限制 |

### 几个字段的设计理由（改之前先想清楚）

- **`row_num` 必须与用户在 Excel 里看到的行号一致**。表头占第 1 行，所以第一条数据行是 2。
  用 0 起或按「数据行序号」报，用户拿着失败明细在文件里根本找不到那一行。
- **`params` 存序列化的查询对象**。导出跑在异步线程里，HTTP 请求早就结束了，
  只能把筛选条件随任务一起落库，worker 再还原回来。序列化/反序列化要走**和接口同一个
  ObjectMapper**（全局 `JacksonConfig`），否则自定义日期格式在两边表现不一致。
- **`task_no` 是唯一对外暴露的标识**。按方向加前缀（`I` = import / `E` = export），
  日志里一眼能看出方向；再加时间戳与随机后缀保证唯一。加唯一索引。
- **`created_by` 是权限模型的基础**。任务中心不做权限码，靠它过滤「只能看自己的任务」。
- **`fail_rows > 0` 决定失败明细能不能下载**。明细是**按需生成**的视图（下载时流式拼出来），
  **不落盘** —— 否则每导一次就多一个文件，临时目录会被明细塞满。

## 状态机

```
PENDING ──► RUNNING ──► SUCCESS
                    └─► FAILED
```

- **行级失败不会让任务变 FAILED**。500 行里坏 3 行是 `SUCCESS` + `fail_rows=3` + 可下载明细。
  `FAILED` 只留给「任务根本跑不起来」（文件读不了、基础设施异常）。
- 状态回写要用**独立短事务**（`REQUIRES_NEW` 语义），否则后续业务数据回滚会把「失败记录」一起抹掉。
- worker 调用必须包一层 catch，把逃逸的异常记成 `FAILED` 并写可读原因。
  **线程池里未捕获的异常会让记录永远停在 `RUNNING`** —— 这是「任务卡住」的头号原因。

## `excel.task.*` 逐项含义

配置在 `spring-shop-web/src/main/resources/application.yml`，绑定在 `ExcelTaskProperties`。

| 配置 | 默认 | 含义 / 改它的后果 |
|---|---|---|
| `enabled` | `true` | 总开关。关掉后提交任务直接报错（`EXCEL_TASK_DISABLED`） |
| `tmp-dir` | `${java.io.tmpdir}/spring-shop/excel` | 源文件与结果文件的落盘根目录。**必须落在容器可写的本地盘** —— 导出是「先写盘、后下载」，用磁盘而非内存，十万行结果文件才不会撑爆堆 |
| `core-pool-size` / `max-pool-size` | 2 / 4 | 独立线程池。**不要混进默认线程池**：导入导出是长耗时任务，会把无关业务拖死 |
| `queue-capacity` | 50 | 队列满时**直接拒绝**（`AbortPolicy` → `EXCEL_TASK_BUSY`）。**不要改成 CallerRuns** —— 那会让 HTTP 线程亲自去跑几万行导入 |
| `keep-alive-seconds` | 120 | 空闲线程回收 |
| `import-batch-size` | 500 | 一批多少行写一次库。批越大吞吐越高，但单事务持锁时间越长 |
| `export-page-size` | 5000 | 一页多少行写一次 Excel。**这个值就是导出的内存上限**（同一时刻只有一页 + Fesod 百行写缓存） |
| `max-import-rows` | 100000 | 单次导入数据行上限，超出**直接报错而不是截断**，防止超大文件把库写穿 |
| `max-error-rows` | 10000 | 失败明细最多落库条数，防止全错文件把明细表写爆 |
| `progress-interval` | 500 | 每处理多少行回写一次进度。回写太频繁会给数据库带来无谓写压力 |
| `file-retain-hours` | 24 | 临时文件保留小时数，超期由清理任务删除 |
| `cleanup-cron` | `0 30 3 * * ?` | 清理任务时间。**`zone` 必须显式指定** —— cron 用 JVM 默认时区，容器里通常是 UTC，不指定就会在错误的时刻跑 |

## worker 契约

`ExcelTaskContext` 是 worker 唯一的入口，成员用途：

| 成员 | 用途 |
|---|---|
| `getTaskNo()` | 日志关联 |
| `getAdminId()` | **受理时捕获的**管理员 id（见下方线程亲和性） |
| `getFileName()` | 决定 Excel 类型 |
| `openSource()` | 打开上传的源文件，调用方负责关闭 |
| `createOutputFile()` | 分配导出目标文件 |
| `params(Class<T>)` | 还原导出查询条件 |
| `reportProgress(processed, success, fail)` | 受 `progress-interval` 节流 |
| `addError(rowNum, message)` / `addErrors(...)` | 行级失败明细 |
| `flush()` / `flushQuietly()` | 强制把待写明细落库 |

构造函数是 `public` 的，**单测可以直接 `new ExcelTaskContext(...)` 驱动 worker**，
不必起真线程池、也不必等异步任务跑完再断言 —— 见 `ProductImportServiceImplTest` /
`AdminUserImportServiceImplTest`。

## 两个线程亲和性陷阱

1. **`ThreadLocal` 不跨线程**。`UserContext` 是请求级的，worker 里读不到。
   需要什么就在受理时写进任务表，worker 再从表里读。
2. **MyBatis-Plus 的自动填充（`MyMetaObjectHandler`）在 worker 线程里照常工作**，
   但前提是 worker 用的是**真实的 Spring 事务**。`TransactionTemplate` 满足；
   裸 JDBC 调用不满足，`create_time` / `update_time` 会是 null。

## 已知问题：`downloadable` 会带来每次轮询的磁盘 I/O

`ExcelTaskServiceImpl.toVO()` 里对导出任务调 `isDownloadable()`，而后者会调
`fileStorage.exists(filePath)` → `Files.isRegularFile(...)`。

于是**每轮询一次就做一次磁盘 stat**；更值得注意的是 `page()` 也对每行记录调 `toVO`，
所以**任务列表页一页 10 行 = 10 次 stat**。

当前量级完全可以忽略（本地 stat 是微秒级）。真正的问题是它把「算一个派生字段」和「碰 I/O」
绑在了一起：如果以后把 `ExcelFileStorage` 换成对象存储，`exists` 就是一次 HTTP HEAD，
延迟从微秒变成几十毫秒，且随页大小线性增长。

修法（改动很小，二选一）：

- 列表页不算 `downloadable`（置 `null`），只在详情接口算；或
- 把判断降级成 `status == SUCCESS && filePath != null`，**真实的存在性检查留给下载接口**
  —— 下载接口本来就必须处理「文件已被清理」这一分支（`EXCEL_TASK_NO_RESULT`），
  在那里检查一次即可，不会漏。

## 相关错误码（`ResultCode` 41~46，公共段）

| 码 | 枚举 | 实际文案 | 触发条件 |
|---|---|---|---|
| 41 | `EXCEL_TASK_NOT_FOUND` | 任务不存在或已被清理，请刷新任务列表 | 任务号查不到，**或不是当前管理员的**（刻意不区分，不给探测他人任务号的机会） |
| 42 | `EXCEL_TASK_DUPLICATE` | 已有同类任务正在执行，请等待其完成后再提交 | **同一管理员 + 同一 `biz_type` 已有未结束任务**（`assertNotRunning` → `countRunningTasks`）。不是「任务号重复」 |
| 43 | `EXCEL_TASK_BUSY` | 系统繁忙，任务排队已满，请稍后重试 | 线程池队列满，`AbortPolicy` 拒绝 |
| 44 | `EXCEL_TASK_DISABLED` | 导入导出功能当前不可用 | `excel.task.enabled=false` |
| 45 | `EXCEL_TASK_NOT_FINISHED` | 任务尚未完成，暂时无法下载结果 | 对非终态任务调下载接口 |
| 46 | `EXCEL_TASK_NO_RESULT` | 任务没有可下载的结果文件 | 导出文件已被清理任务删除；或导入任务没有失败行却要下明细 |

> ⚠️ **42 号是「防重复提交」而不是「任务号冲突」** —— 这是本框架的一条隐式约束：
> 同一个管理员对同一个业务类型**同时只能有一个进行中的任务**。
> 给新业务接导入/导出时，如果业务上允许并发提交（比如批量脚本连点），
> 需要知道这里会直接拒绝，而不是排队。

> 通用版（不含本仓库具体路径与配置默认值）在跨项目技能 `fesod-async-excel-task` 里。
> 本文件是本仓库的事实来源；改动配置语义时**以本文件为准**。
