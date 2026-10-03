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
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        List<Long> requestedPages = new ArrayList<>();
        List<DemoRow> all = demoRows(3); // 两页：2 + 1

        int exported = ExcelExportSupport.export(context, DemoQuery.class, DemoRow.class, "测试",
                (query, current, pageSize) -> {
                    requestedPages.add(current);
                    return page(all, current, pageSize);
                });

        assertEquals(3, exported);
        // 第 2 页只有 1 行（不满），说明已取完，不该再发一次必然为空的查询
        assertEquals(List.of(1L, 2L), requestedPages);
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
                (query, current, pageSize) -> {
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
                (query, current, pageSize) -> page(all, current, pageSize));
    }

    /**
     * 按页切片，模拟数据库分页查询
     */
    private List<DemoRow> page(List<DemoRow> all, long current, long pageSize) {
        int from = (int) ((current - 1) * pageSize);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, (int) Math.min(all.size(), from + pageSize));
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
