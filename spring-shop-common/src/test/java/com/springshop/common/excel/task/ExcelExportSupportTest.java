package com.springshop.common.excel.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ExcelExportSupport;
import com.springshop.common.excel.ExcelFileType;
import com.springshop.common.excel.ExcelReadOptions;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 分页流式导出模板方法测试
 *
 * <p>四个导出（商品 / 管理员 / 操作日志 / 订单）共用 {@link ExcelExportSupport#export}，
 * 所以这里的边界用例就是所有导出的边界用例：
 * 「分页翻到底不漏行不重复」「最后一页不满要提前结束」「一条都没有也要写出表头」。
 *
 * <p>分页方式已由 OFFSET 改为 keyset（游标），因此这里还额外钉住了改动的原因：
 * 导出期间有并发写入时，OFFSET 的窗口会整体后移导致重复/漏行，
 * 而游标只认「比上一页最后一条更小的 id」，窗口不会移动。
 */
class ExcelExportSupportTest {

    @TempDir
    Path tmpDir;

    private ExcelTaskProperties properties;

    private ExcelTaskService taskService;

    private ExcelTaskContext context;

    /** 导出模板方法内部自己建结果文件，测试通过拦截 updateFilePath 找回它 */
    private Path outputPath;

    @BeforeEach
    void setUp() {
        properties = new ExcelTaskProperties();
        properties.setTmpDir(tmpDir.toString());
        taskService = mock(ExcelTaskService.class);
        doAnswer(invocation -> {
            outputPath = Path.of(invocation.getArgument(1, String.class));
            return null;
        }).when(taskService).updateFilePath(anyString(), anyString());
        context = contextFor(null);
    }

    @Test
    void export_shouldWalkAllPagesAndWriteEveryRow() throws IOException {
        properties.setExportPageSize(2);
        // 5 行 / 每页 2 行 → 三页：2、2、1，最后一页不满即收尾
        List<DemoRow> all = demoRows(5);

        int exported = exportFrom(all);

        assertEquals(5, exported);
        assertEquals(5, readRows(outputPath).size(), "翻页不得漏行或重复");
    }

    @Test
    void export_shouldStopAfterShortPage() throws IOException {
        properties.setExportPageSize(2);
        List<Long> requestedCursors = new ArrayList<>();
        List<DemoRow> all = demoRows(3); // 两页：2 + 1

        int exported = ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                DemoRow::getId,
                (query, lastId, pageSize) -> {
                    requestedCursors.add(lastId);
                    return pageAfter(all, lastId, pageSize);
                });

        assertEquals(3, exported);
        // 第一页不带游标；第二页带上一页最后一条的 id（倒序导出，3 行里第 2 条 = 2）。
        // 第 2 页只剩 1 行（不满），说明已取完，不该再发一次必然为空的查询
        assertEquals(Arrays.asList(null, 2L), requestedCursors);
    }

    @Test
    void export_shouldNotDuplicateOrSkipRowsWhenDataIsInsertedMidExport() throws IOException {
        properties.setExportPageSize(2);
        // 导出期间有并发写入：第一页取完后，新插入一条更大的 id（新订单 / 新日志就是这么产生的）
        List<DemoRow> table = new ArrayList<>(demoRows(5));
        List<Long> exportedIds = new ArrayList<>();

        int exported = ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                DemoRow::getId,
                (query, lastId, pageSize) -> {
                    List<DemoRow> page = pageAfter(table, lastId, pageSize);
                    if (lastId == null) {
                        table.add(new DemoRow(100L, "新插入"));
                    }
                    page.forEach(row -> exportedIds.add(row.getId()));
                    return page;
                });

        // 游标锚在 id 上，插入数据不会让窗口整体后移：既没有重复导出写过的行，
        // 也没有整页跳过排在边界上的行
        assertEquals(5, exported);
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), exportedIds);
    }

    @Test
    void offsetPagination_shouldDuplicateRowsUnderTheSameConcurrency() {
        // 对照组：同一场景换成 OFFSET 分页重跑一遍，用于说明「为什么必须改成 keyset」。
        // 这段不参与生产代码，是本次改动的可执行依据。
        List<DemoRow> table = new ArrayList<>(demoRows(5));
        List<Long> offsetIds = new ArrayList<>();
        for (long current = 1; ; current++) {
            List<DemoRow> page = offsetPage(table, current, 2);
            if (page.isEmpty()) {
                break;
            }
            page.forEach(row -> offsetIds.add(row.getId()));
            if (current == 1) {
                // 与 keyset 用例同一时点插入同一条数据
                table.add(new DemoRow(100L, "新插入"));
            }
            if (page.size() < 2) {
                break;
            }
        }

        // 第 2 页的窗口整体后移了一行：id=4 在第一页已经导出过，这里又被导出一次
        assertEquals(List.of(5L, 4L, 4L, 3L, 2L, 1L), offsetIds);
        assertNotEquals(List.of(5L, 4L, 3L, 2L, 1L), offsetIds,
                "OFFSET 分页在并发写入下会重复导出行，这正是本次改成 keyset 的原因");
    }

    @Test
    void export_shouldStopWhenCursorDoesNotAdvance() throws IOException {
        properties.setExportPageSize(2);
        // 取数实现忘了按游标过滤（或行模型取不到主键）时，每一页都会取回同一批数据。
        // 不拦的话任务会一直往同一个文件里写，直到磁盘写满 —— 必须提前收尾。
        List<DemoRow> all = demoRows(4);

        int exported = ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                DemoRow::getId,
                // 无视 lastId，每页都返回同一批数据（等价于「忘了按游标过滤」）
                (query, lastId, pageSize) -> pageAfter(all, null, pageSize));

        assertEquals(4, exported, "游标不推进时应当只导出两页就收尾，而不是死循环");
        assertEquals(4, readRows(outputPath).size());
    }

    @Test
    void export_shouldStopWhenRowModelHasNoId() throws IOException {
        properties.setExportPageSize(2);
        // idOf 取不到主键（返回 null）时同样必须收尾：拿不到游标就无法安全地翻下一页，
        // 宁可少导一页（日志里有 warn），也不能把 null 当游标一直翻下去
        List<DemoRow> all = demoRows(4);

        int exported = ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                row -> null,
                (query, lastId, pageSize) -> pageAfter(all, lastId, pageSize));

        assertEquals(2, exported, "第一页取满后拿不到游标，应当收尾");
        assertEquals(2, readRows(outputPath).size());
    }

    @Test
    void export_withNoData_shouldStillWriteHeader() throws IOException {
        properties.setExportPageSize(10);

        int exported = exportFrom(List.of());

        assertEquals(0, exported);
        assertTrue(Files.isRegularFile(outputPath), "即使没有数据也要生成文件");
        // 关键：写出的是「只有表头」的合法工作簿，而不是一个打不开的空文件。
        // 用户打开它能看到列名，从而区分「确实没数据」与「导出坏了」。
        // headRowNumber(0) 把表头也当数据读回来，于是「表头确实写进去了」可以被直接断言。
        List<ExcelRow> headerRows = readRowsWithHeader(outputPath);
        assertEquals(1, headerRows.size(), "无数据时应当只剩表头这一行");
        assertEquals("编号", headerRows.get(0).cell(0));
        assertEquals("名称", headerRows.get(0).cell(1));
    }

    @Test
    void export_shouldRestoreQueryFromTaskParams() throws IOException {
        properties.setExportPageSize(10);
        DemoQuery saved = new DemoQuery();
        saved.setKeyword("手机");
        saved.setStatus(1);
        context = contextFor(new ObjectMapper().writeValueAsString(saved));

        List<DemoQuery> seen = new ArrayList<>();
        ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                DemoRow::getId,
                (query, lastId, pageSize) -> {
                    seen.add(query);
                    return List.of();
                });

        assertEquals(1, seen.size(), "至少取一页，据此验证条件已还原");
        assertEquals("手机", seen.get(0).getKeyword());
        assertEquals(1, seen.get(0).getStatus());
    }

    @Test
    void export_shouldReportProgressAndFilePath() throws IOException {
        properties.setExportPageSize(2);
        // 测试数据只有几行，把进度上报间隔压到 1 才能观察到「按页回写」
        properties.setProgressInterval(1);

        exportFrom(demoRows(3));

        // 进度按页上报（绝对计数），前端据此画进度条
        verify(taskService, atLeastOnce()).updateProgress(eq("E-TEST"), anyInt(), anyInt(), anyInt());
        // 结果文件路径要在写内容之前回写，避免出现「磁盘上有文件但系统不知道属于谁」
        verify(taskService, atLeastOnce()).updateFilePath(eq("E-TEST"), anyString());
    }

    // ---------- 测试辅助 ----------

    private int exportFrom(List<DemoRow> all) throws IOException {
        return ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                DemoRow::getId,
                (query, lastId, pageSize) -> pageAfter(all, lastId, pageSize));
    }

    /**
     * 模拟 keyset 取一页：{@code ORDER BY id DESC} 且 {@code WHERE id < lastId}，
     * 与生产代码里四个 {@code exportPage} 的写法一致（倒序导出用 {@code lt}）。
     */
    private List<DemoRow> pageAfter(List<DemoRow> all, Long lastId, long pageSize) {
        return all.stream()
                .filter(row -> lastId == null || row.getId() < lastId)
                .sorted(Comparator.comparingLong(DemoRow::getId).reversed())
                .limit(pageSize)
                .toList();
    }

    /** 模拟 OFFSET 取一页：{@code ORDER BY id DESC LIMIT pageSize OFFSET (current-1)*pageSize} */
    private List<DemoRow> offsetPage(List<DemoRow> all, long current, long pageSize) {
        List<DemoRow> sorted = all.stream()
                .sorted(Comparator.comparingLong(DemoRow::getId).reversed())
                .toList();
        int from = (int) ((current - 1) * pageSize);
        if (from >= sorted.size()) {
            return List.of();
        }
        return sorted.subList(from, (int) Math.min(sorted.size(), from + pageSize));
    }

    private List<DemoRow> demoRows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new DemoRow((long) i, "商品" + i))
                .toList();
    }

    private List<ExcelRow> readRows(Path path) throws IOException {
        return readRows(path, 1);
    }

    /**
     * 把表头也当数据行读回来（{@code headRowNumber(0)}），用于断言「表头写了什么」
     */
    private List<ExcelRow> readRowsWithHeader(Path path) throws IOException {
        return readRows(path, 0);
    }

    private List<ExcelRow> readRows(Path path, int headRowNumber) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return ExcelSupport.readAll(in, ExcelReadOptions.defaults()
                    .fileType(ExcelFileType.XLSX)
                    .headRowNumber(headRowNumber)
                    .maxRows(1000));
        }
    }

    private ExcelTaskContext contextFor(String params) {
        ExcelTask task = new ExcelTask();
        task.setTaskNo("E-TEST");
        task.setBizType("DEMO_EXPORT");
        task.setFileName("导出.xlsx");
        task.setTaskType(ExcelTaskType.EXPORT.getCode());
        task.setStatus(ExcelTaskStatus.PENDING.getCode());
        task.setParams(params);
        task.setCreatedBy(1L);
        return new ExcelTaskContext(task, properties, taskService,
                new ExcelFileStorage(properties), new ObjectMapper());
    }

    /** 导出查询条件（字段随便，只要能被 Jackson 还原） */
    public static class DemoQuery {

        private String keyword;

        private Integer status;

        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
    }

    /** 导出行模型：列名与顺序由注解决定 */
    public static class DemoRow {

        @ExcelProperty("编号")
        private Long id;

        @ExcelProperty("名称")
        private String name;

        public DemoRow() {
        }

        public DemoRow(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
