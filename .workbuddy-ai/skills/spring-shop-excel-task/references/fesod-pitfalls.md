# Fesod 读写：坑与正确写法

依赖：`org.apache.fesod:fesod-sheet`（Apache 孵化中），EasyExcel / FastExcel 的继任者。
大部分 EasyExcel 经验可以直接迁移，**除了下面三个「不报错但结果不对」的坑**。

**不要靠记忆猜 API。** 直接读字节码确认：

```bash
ls ~/.m2/repository/org/apache/fesod/fesod-sheet/*/fesod-sheet-*.jar

javap -cp <jar> 'org.apache.fesod.sheet.read.builder.ExcelReaderBuilder'
javap -cp <jar> 'org.apache.fesod.sheet.write.builder.ExcelWriterBuilder'
javap -cp <jar> 'org.apache.fesod.sheet.read.listener.ReadListener'
javap -cp <jar> 'org.apache.fesod.sheet.enums.ExcelTypeEnum'
javap -cp <jar> 'org.apache.fesod.sheet.enums.ReadDefaultReturnEnum'
```

---

## 坑 1 —— `head()` 是列优先，表头会被转置

`ExcelWriterBuilder.head(List<List<String>>)` 把**外层 list 当列**。传一个普通表头列表，
写出来是「N 行 × 1 列」的转置表头。

```java
// 错：表头被转置
builder.head(List.of("商品名称*", "分类名称*", "SKU编码*"));

// 对：每个表头各自包一层
private static List<List<String>> toColumnMajorHead(List<String> headers) {
    List<List<String>> head = new ArrayList<>(headers.size());
    for (String h : headers) {
        head.add(List.of(h));
    }
    return head;
}
```

仓库里已经处理好了：`ExcelStreamWriter.ofHeaders(...)` 内部调用 `toColumnMajorHead`。
**不要绕过它直接调 Fesod 的 `head(...)`。**

## 坑 2 —— 自动探测文件类型会静默退化成 CSV

不显式指定类型时，损坏/非 Excel 的字节**不会报错**，Fesod 会退化成 CSV 解析并返回 **0 行**。
调用方于是把「文件坏了」报成「没有数据」。

```java
// 错：损坏文件读成 0 行，无异常
Fesod.read(inputStream);

// 对：由文件名判定并显式传入
ExcelReadOptions options = ExcelReadOptions.defaults()
        .fileType(ExcelFileType.fromFileName(context.getFileName()));
ExcelSupport.readAll(in, options);
```

读失败要转成可读的业务错误（`ProductImportServiceImpl.readRows` 里把 `ExcelReadException`
转成 `PRODUCT_IMPORT_FILE_INVALID`），而不是让空结果流到校验层。

## 坑 3 —— 零行不建 sheet

Fesod 在第一次 `write` 时才懒创建 sheet。什么都不写就得到一个**没有 sheet 的工作簿**，
回读直接抛 `Can not find any sheet!`。

```java
// 对：一条都没查到也要走一遍 write，把表头挤出来
if (!wroteAny) {
    writer.write(List.of());
}
```

这条是「导出 0 行」场景能拿到合法文件的原因。测试里要读回这种只有表头的文件，
必须设 `headRowNumber(0)`，让表头也算一行，否则读不到数据行会直接抛错。

---

## 依赖陷阱：不要另外声明 `poi-ooxml`

`fesod-sheet` 会传递引入自带版本的 POI。额外声明 `poi-ooxml`（往往还是另一个版本）
会在运行期解析到不匹配的 POI，抛 `NoSuchMethodError`。**只声明 `fesod-sheet`。**

## 流式读的要点

封装在 `common/excel/ExcelSupport`：

- 用 `ReadDefaultReturnEnum.STRING` 口径，**每个单元格都当文本取回**，
  由我们自己解析（`BigDecimal` / `Integer` / 空值判定）。交给 Fesod 做类型转换，
  等于把报错时机挪出了校验层。
- 在 `ReadListener.invoke` 里**就地卡行数上限**（`ExcelReadOptions.maxRows`），
  超限抛专用异常直接失败，**不静默截断**。
- 同时卡列数上限（`maxColumns`），畸形文件否则会用每行一个巨大数组把内存打爆。
- `onException` 必须把异常抛出去，**不要吞**。

## 流式写的要点

封装在 `common/excel/ExcelStreamWriter`：

- **按页写**。同一时刻只有「一页数据 + Fesod 内部百行写缓存」在内存里，
  所以十万行导出和一千行导出的内存占用基本相同。页大小由
  `excel.task.export-page-size` 控制，**这个值就是导出的内存上限**。
- `finish()` 必须调用，否则得到一个截断的 zip，Excel 打不开。
- `close()` 做成幂等的，避免 try-with-resources 与显式 `finish()` 双重关闭。
- `autoCloseStream(false)`，流的生命周期交给调用方。

## 类型解析的口径

不要信任单元格自动转换，`ExcelSupport` 里维护显式解析：

- `parseDecimal(String)` —— trim → 空值返回 null → 不可解析返回 null → 再校验精度。
  **`DECIMAL(10,2)` 会静默四舍五入，价格上不能接受这种「悄悄改数」**：
  超过 2 位小数或超过 8 位整数直接判该行失败，而不是让数据库去 round。
- `parseInt(String)` —— 同样形状。
- `isBlankText(String)` —— null 安全，纯空白算空。

**空值 vs 零值的语义要分清**：可选数值列留空应映射成默认值（如库存 `0`），
而**填了但解析不出来**必须是行级失败。把这两者混为一谈会让「填错」静默变成「默认值」。

---

> 通用版（不含本仓库的封装类名）在跨项目技能 `fesod-async-excel-task` 里，
> 内容与本文件基本一致。**改了一处记得改另一处**，或者只保留本文件为准。
