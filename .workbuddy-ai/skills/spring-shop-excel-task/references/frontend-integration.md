# 前端对接：上传、轮询、下载

## 核心结论

**上传和下载都只发生一次，只有「任务进度」需要轮询。**

一次完整的导入/导出 = **1 次上传或提交 + N 次轮询 + 1 次下载**。

| 动作 | 交互模式 | 为什么 |
|---|---|---|
| 上传（导入） | 一次 multipart POST，同步等响应 | 只是把文件存到磁盘，秒级 |
| 提交（导出） | 一次 JSON POST，同步等响应 | 只是建一条任务记录，毫秒级 |
| 进度 | **轮询** | 真正的耗时活在这里 |
| 下载 | 一次 GET，等它传完 | 文件已经准备好了，没有等待 |

## 接口清单

### 提交（同步受理，立刻返回 `ExcelTaskVO`）

| 动作 | 方法 | 路径 | 权限码 | 入参 |
|---|---|---|---|---|
| 导入管理员 | POST | `/api/admin/users/import` | `system:user:import` | multipart，字段名 `file` |
| 导入商品 | POST | `/api/admin/products/import` | `product:product:import` | multipart，字段名 `file` |
| 导出管理员 | POST | `/api/admin/users/export` | `system:user:list` | JSON `AdminUserExportQuery` |
| 导出商品 | POST | `/api/admin/products/export` | `product:product:list` | JSON `ProductExportQuery` |
| 导出订单 | POST | `/api/admin/orders/export` | `order:order:list` | JSON `OrderExportQuery` |
| 导出操作日志 | POST | `/api/admin/operation-logs/export` | `system:log:list` | JSON `OperationLogExportQuery` |

> **导出的权限码是「列表」权限，不是单独的 export 权限** —— 能看列表就能导出。
> 导出查询对象里通常有一个 `ids` 字段：传了就是「只导出选中的」，不传就是「按筛选条件导出全部」。

### 任务中心

| 动作 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 我的任务列表 | GET | `/api/admin/excel-tasks` | 登录即可（`isAuthenticated()`） |
| 任务详情/进度 | GET | `/api/admin/excel-tasks/{taskNo}` | 登录即可 |
| 下载结果 | GET | `/api/admin/excel-tasks/{taskNo}/download` | 登录即可 |

> 任务中心刻意用**归属校验**（`created_by`）而不是权限码：能提交某个任务说明提交时已经过了权限码校验，
> 所以不必为任务中心再造一套权限码与菜单种子数据。**只能查自己的任务**，
> 查别人的任务号会返回「任务不存在」（刻意不区分，不给探测他人任务号的机会）。

## `ExcelTaskVO` 字段与前端用法

| 字段 | 前端怎么用 |
|---|---|
| `taskNo` | 轮询和下载都用它 |
| `status` | **主判断依据**。0 待执行 / 1 执行中 / 2 成功 / 3 失败 |
| `statusName` | 直接显示，不用自己映射 |
| `progress` | 进度百分比。⚠️ **导出任务完成前恒为 0 或 1，不要用它画进度条** |
| `totalRows` / `processedRows` / `successRows` / `failRows` | 导入任务可用来显示「已处理 1200/5000，成功 1198，失败 2」 |
| `downloadable` | **决定下载按钮是否可点**，不要自己拼判断逻辑 |
| `errorMsg` | 任务级失败原因，`status === 3` 时显示 |
| `taskType` | 1 导入 / 2 导出。**决定下载的是什么**：导出下结果文件，导入下失败明细 |

## ⚠️ 导入的终态有三种，别把 `status=2` 读成「全部导入成功」

这是最容易在前端 UI 上翻车的一点。**行级失败不改变任务成功状态** ——
一个 500 行里有 3 行坏的文件，任务状态是 **`status=2` 成功**，同时 `failRows=3`。

所以「任务执行完」对导入来说有三种结局：

| 结局 | `status` | `failRows` | 真实含义 | 下载按钮 |
|---|---|---|---|---|
| 全部成功 | 2 | 0 | 每一行都进库了 | **不显示**（`downloadable=false`） |
| **部分成功** | **2** | **>0** | **有若干行没进去** | 显示，下载的是**失败明细** |
| 完全失败 | 3 | 视情况 | 一行都没进去（文件读不了等） | `failRows>0` 时显示 |

**前端文案必须区分前两种**，不能统一弹「导入成功」：

```js
if (vo.status === 2) {
  if (vo.failRows > 0) {
    // 不能说「导入成功」
    message.warning(`导入完成：成功 ${vo.successRows} 行，失败 ${vo.failRows} 行，请下载失败明细`);
  } else {
    message.success(`导入成功：共 ${vo.successRows} 行`);
  }
} else if (vo.status === 3) {
  message.error(`导入失败：${vo.errorMsg}`);
}
```

同样地，`downloadable` 是后端算好的，**导入任务在没有失败行时就是 `false`** ——
不要在前端自己拼 `status === 2` 来判断要不要显示下载按钮。

## 导入的下载按钮 ≠ 导出结果

| 任务类型 | 下载按钮给的是什么 | 用户拿它做什么 |
|---|---|---|
| 导出 | **结果文件**（我要的数据） | 拿走、分析 |
| 导入 | **失败明细**（哪些行错了、为什么） | 改完重传 |

导入的失败明细是**按需生成**的（流式读 `excel_task_error` 拼出来的），不占磁盘。
所以它在 `failRows > 0` 时随时可下，即使文件已经超过 24 小时保留期 ——
保留期只影响导出结果文件的**磁盘文件**。

## 标准流程

```js
const TERMINAL = new Set([2, 3]); // 2 成功 / 3 失败

// 1. 提交 —— 一次
async function submitImport(file) {
  const form = new FormData();
  form.append('file', file);              // 字段名必须是 file
  const { data } = await axios.post('/api/admin/products/import', form);
  return data.data;                        // ExcelTaskVO，秒级返回
}

async function submitExport(query) {
  const { data } = await axios.post('/api/admin/products/export', query);
  return data.data;
}

// 2. 轮询 —— N 次
async function pollUntilFinished(taskNo, onTick, {
  interval = 2000,
  timeout = 10 * 60 * 1000,
} = {}) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const { data } = await axios.get(`/api/admin/excel-tasks/${taskNo}`);
    const vo = data.data;
    onTick(vo);
    if (TERMINAL.has(vo.status)) return vo;
    if (Date.now() > deadline) throw new Error('任务超时，请到任务中心查看');
    await new Promise((r) => setTimeout(r, interval));
  }
}

// 3. 下载 —— 一次
async function download(taskNo) { /* 见下节，有三个坑 */ }
```

### 轮询的四个纪律

1. **必须设超时**。任务卡在 RUNNING 时无限轮询会一直打接口。
2. **页面不可见时暂停**（`document.hidden`），回来再续 —— 省掉大量无谓请求。
3. **组件卸载要清定时器 / 中断循环**，否则用户切走页面后还在后台打接口。
4. **间隔 2 秒左右**。后端一次轮询 = 一次单行查询 + 约 1 KB JSON，成本很低，但没必要更密。

## 下载的三个坑

### 坑 1：不能用 `<a href>` 或 `window.open()`

JWT **只从 `Authorization: Bearer xxx` 请求头读**（见 `spring-shop-web/.../JwtAuthenticationFilter`），
不支持 query param，也不支持 Cookie。浏览器发起的导航请求不带这个头 → 401。

必须用 fetch/axios 拿 blob，再造 object URL 触发下载。

### 坑 2：文件名在 `Content-Disposition` 里，且是 RFC 5987 编码

后端返回的是：

```
Content-Disposition: attachment; filename*=UTF-8''%E5%95%86%E5%93%81.xlsx
```

注意是 **`filename*`（带星号）** 而不是 `filename`，值需要 `decodeURIComponent`。
（下载响应刻意不设 charset，避免 Tomcat 把 `;charset=UTF-8` 拼进 Content-Type。）

### 坑 3（最阴）：失败时 HTTP 仍是 200，但响应体是 JSON

项目约定「业务失败走 HTTP 200 + 业务码」。下载接口失败时：

- HTTP 状态：**200**
- `Content-Type`：`application/json`
- 响应体：`{"code":46,"message":"任务没有可下载的结果文件"}`
- 但 `Content-Disposition: attachment` **还挂着** —— 因为 Controller 先设了响应头，
  异常是之后才抛的，而异常处理器不会清掉已设的响应头

前端用 `responseType: 'blob'` 会把这个 JSON **当 Blob 收下来**。
如果不检查类型就直接存文件，**用户会下载到一个内容是错误信息的「xlsx」**。

必须在拿到 blob 后检查类型：

```js
function parseFilename(disposition, fallback) {
  if (!disposition) return fallback;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (star) return decodeURIComponent(star[1]);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  return plain ? plain[1] : fallback;
}

async function download(taskNo) {
  const res = await axios.get(`/api/admin/excel-tasks/${taskNo}/download`, {
    responseType: 'blob',          // 关键：不加会把 xlsx 当文本处理，损坏文件
  });
  const blob = res.data;

  // 关键：后端失败时返回的是 JSON，不是 xlsx
  if (blob.type.includes('application/json')) {
    const { code, message } = JSON.parse(await blob.text());
    throw new Error(`[${code}] ${message}`);
  }

  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = parseFilename(res.headers['content-disposition'], `${taskNo}.xlsx`);
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);      // 必须回收，否则 blob 常驻内存
  }
}
```

## 后端约束（前端容易踩）

| 约束 | 现象 | 前端应该做什么 |
|---|---|---|
| 同一管理员 + 同一业务类型只能有一个进行中任务 | 重复提交 → `code 42`「已有同类任务正在执行，请等待其完成后再提交」 | 提交按钮置 loading；拿到 42 时提示并跳到那个进行中的任务 |
| 线程池队列上限 50 | `code 43`「系统繁忙，任务排队已满，请稍后重试」 | 提示稍后重试 |
| 总开关关闭 | `code 44`「导入导出功能当前不可用」 | 隐藏导入/导出入口 |
| 上传文件上限 10MB | 超过 → `code 400`「上传文件过大」 | 前端先做大小校验，体验更好 |
| 单次导入行数上限 10 万 | 超出 → 任务直接失败 | 提示分文件 |
| **导出任务的 `progress` 完成前恒为 0/1** | 进度条不动 | 导出用 `status` 显示「处理中」；只有导入能用百分比 |
| 结果文件保留 24 小时 | 超期下载 → `code 46` | 列表页对超期任务禁用下载按钮 |
| 导出没有行数上限 | 可能导出整表 | 大数据量时提示用户加筛选条件 |

> 关于「导出 `progress` 恒为 0/1」：`total_rows` 只在 `markSuccess` 时才写入，
> 所以 `resolveProgress` 在导出过程中恒返回 1。这是已知问题，
> 见 `task-internals.md` 与 `docs/superpowers/plans/` 里的盲区清单。

## 两种交互形态

1. **就地弹窗**（推荐给「点导出」场景）：提交 → 弹进度 → 完成 → 显示下载按钮。用户不离开当前页。
2. **任务中心列表页**：`GET /api/admin/excel-tasks` 分页拉历史任务。**列表页不需要轮询** ——
   用户手动刷新即可；只有「正在跑的任务」才值得轮询。

两者共用同一套接口。
