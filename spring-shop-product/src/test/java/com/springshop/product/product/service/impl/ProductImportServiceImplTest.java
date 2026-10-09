package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.ExcelReadOptions;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.excel.task.ExcelFileStorage;
import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskError;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.ExcelTaskService;
import com.springshop.common.excel.task.ExcelTaskStatus;
import com.springshop.common.excel.task.ExcelTaskType;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品批量导入执行体单元测试
 *
 * <p>用 {@link ExcelSupport#writeDynamic} 真实生成 xlsx，再直接驱动
 * {@code processImport}（异步任务的执行体），覆盖「解析 → 分组 → 校验 → 分批落库」全链路。
 *
 * <p><b>为什么不通过 submitImport 走线程池</b>：那会让断言与异步线程赛跑，
 * 要么轮询要么 sleep，既慢又不稳。执行体是包级可见的普通方法，直接调用即可，
 * 线程池调度本身由 web 模块的集成测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
class ProductImportServiceImplTest {

    private static final String TASK_NO = "I-TEST";

    private static final List<String> HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    private static final String CATEGORY_NAME = "手机";

    @TempDir
    Path tmpDir;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductCategoryMapper categoryMapper;

    @Mock
    private ExcelTaskExecutor excelTaskExecutor;

    @Mock
    private ExcelTaskService taskService;

    private ExcelTaskProperties properties;

    private ProductImportServiceImpl importService;

    /** 当前用例的任务上下文（由 runImport 赋值），断言进度与失败计数都读它 */
    private ExcelTaskContext context;

    @BeforeEach
    void setUp() {
        properties = new ExcelTaskProperties();
        properties.setTmpDir(tmpDir.toString());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        // lenient：只有真正落库的用例才会开事务，其余用例不该因为「没用到这个桩」而失败
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        importService = new ProductImportServiceImpl(productMapper, productSkuMapper, categoryMapper,
                excelTaskExecutor, properties, transactionManager);
    }

    /**
     * 预热 MyBatis-Plus 的 Lambda 元数据（沿用 product / cart / order 模块单测的既有做法）
     *
     * <p>{@code LambdaQueryWrapper} 的 {@code in(...)} 会在打桩前就解析列名，
     * 缺少 TableInfo 时直接抛「can not find lambda cache for this entity」，
     * 因此必须在纯 Mockito 环境下手动初始化。
     */
    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { Product.class, ProductSku.class, ProductCategory.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @Test
    void importProducts_should_group_same_name_rows_into_one_product() throws IOException {
        stubCategory();
        stubInsertAssignsId();

        runImport(List.of(
                row("手机A", "SKU-A-001", "199.00", "10"),
                row("手机A", "SKU-A-002", "299.00", "20")));

        // 2 行都进了同一组，成功计数按 SKU 计
        assertEquals(2, context.getProcessedRows());
        assertEquals(2, context.getSuccessRows());
        assertEquals(0, context.getFailRows());

        ArgumentCaptor<List<Product>> productCaptor = productListCaptor();
        verify(productMapper).insertBatch(productCaptor.capture());
        List<Product> products = productCaptor.getValue();
        assertEquals(1, products.size(), "同名两行应聚合为一个 SPU");
        assertEquals("手机A", products.get(0).getName());
        assertEquals(5L, products.get(0).getCategoryId());
        assertEquals(0, products.get(0).getSales().intValue());

        ArgumentCaptor<List<ProductSku>> skuCaptor = skuListCaptor();
        verify(productSkuMapper).insertBatch(skuCaptor.capture());
        List<ProductSku> skus = skuCaptor.getValue();
        assertEquals(2, skus.size());
        assertTrue(skus.stream().allMatch(sku -> sku.getProductId().equals(100L)),
                "SKU 的 productId 应由 insertBatch 回填的自增主键补齐");
    }

    @Test
    void importProducts_should_fail_whole_group_when_category_not_found() throws IOException {
        stubCategory();

        runImport(List.of(
                rowWithCategory("手机A", "不存在分类", "SKU-A-001", "199.00", "10"),
                rowWithCategory("手机A", "不存在分类", "SKU-A-002", "199.00", "10")));

        // 缺分类的商品没有任何一行能落库，因此整组一起失败并逐行给出原因
        assertEquals(0, context.getSuccessRows());
        assertEquals(2, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("不存在"));
        verify(productMapper, never()).insertBatch(any());
    }

    @Test
    void importProducts_should_fail_group_when_all_sku_rows_invalid() throws IOException {
        stubCategory();

        runImport(List.of(
                row("手机A", "", "199.00", "10"),
                row("手机A", "", "199.00", "10")));

        // 避免产生「没有 SKU 的空商品」
        assertEquals(0, context.getSuccessRows());
        assertEquals(2, context.getFailRows());
        verify(productMapper, never()).insertBatch(any());
    }

    @Test
    void importProducts_should_skip_invalid_row_but_keep_others() throws IOException {
        stubCategory();
        stubInsertAssignsId();

        runImport(List.of(
                row("手机A", "SKU-A-001", "199.00", "10"),
                // 价格不是数字 → 该行失败
                row("手机A", "SKU-A-002", "abc", "10")));

        assertEquals(1, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertEquals(3, errors().get(0).getRowNum(), "行号应与用户在 Excel 里看到的一致");
        assertTrue(errors().get(0).getMessage().contains("合法数字"));
    }

    @Test
    void importProducts_should_report_duplicate_sku_code_inside_file() throws IOException {
        stubCategory();
        stubInsertAssignsId();

        runImport(List.of(
                row("手机A", "SKU-DUP", "199.00", "10"),
                row("手机B", "SKU-DUP", "299.00", "10")));

        assertEquals(1, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("SKU-DUP"));
    }

    @Test
    void importProducts_should_report_sku_code_occupied_in_db() throws IOException {
        stubCategory();
        // 编码查重与 uk_sku_code 口径一致：逻辑删除的 SKU 仍然占用编码
        when(productSkuMapper.selectOccupiedSkuCodes(any())).thenReturn(List.of("SKU-A-001"));

        runImport(List.of(row("手机A", "SKU-A-001", "199.00", "10")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("已存在"));
    }

    /**
     * {@code product.name} 上没有唯一索引，「同名 + 换一批 SKU 编码」的重传会静默建出
     * 第二个同名 SPU，运营在列表里看到两个一模一样的商品而事后无法合并。
     * 导入必须严格拒绝，并把「导入不会覆盖」「该去哪里改」写进提示。
     */
    @Test
    void importProducts_should_reject_product_name_that_already_exists() throws IOException {
        stubCategory();
        when(productMapper.selectOccupiedProductNames(any())).thenReturn(List.of("手机A"));

        runImport(List.of(
                row("手机A", "SKU-NEW-001", "199.00", "10"),
                row("手机A", "SKU-NEW-002", "199.00", "10")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(2, context.getFailRows(), "整组一起失败，运营才知道这两行属于同一个商品");
        String message = errors().get(0).getMessage();
        assertTrue(message.contains("手机A"), "实际: " + message);
        assertTrue(message.contains("已存在"), "实际: " + message);
        assertTrue(message.contains("不会覆盖"), "提示要说清导入不覆盖已有商品，实际: " + message);
        verify(productMapper, never()).insertBatch(any());
    }

    /**
     * 重名校验要排在分类校验之前：商品名已存在时这次导入对这一组来说注定是空操作，
     * 先报「分类不存在」会把运营引到无关的错处去改。
     */
    @Test
    void importProducts_should_report_name_conflict_before_category_conflict() throws IOException {
        when(productMapper.selectOccupiedProductNames(any())).thenReturn(List.of("手机A"));

        runImport(List.of(rowWithCategory("手机A", "不存在的分类", "SKU-NEW-001", "199.00", "10")));

        String message = errors().get(0).getMessage();
        assertTrue(message.contains("已存在"), "实际: " + message);
        assertFalse(message.contains("分类"), "不应把运营引到分类上去改，实际: " + message);
    }

    /**
     * 「严格拒绝」是按商品粒度拒绝，不是整份文件作废——其余商品照常导入，
     * 这样一次重传里只有真正重复的那个商品会红。
     */
    @Test
    void importProducts_should_reject_only_the_duplicate_name_and_keep_the_rest() throws IOException {
        stubCategory();
        stubInsertAssignsId();
        when(productMapper.selectOccupiedProductNames(any())).thenReturn(List.of("手机A"));

        runImport(List.of(
                row("手机A", "SKU-DUP-1", "199.00", "10"),
                row("手机B", "SKU-NEW-2", "299.00", "10")));

        assertEquals(1, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("已存在"));
    }

    /**
     * 「重名」指的是与库里已有商品重名，<b>不是</b>文件内同名：文件内同名恰恰是
     * 「一个 SPU 多个 SKU」的正常写法，不能被当成重复拒掉。
     */
    @Test
    void importProducts_should_treat_same_name_rows_inside_file_as_one_product_not_duplicate() throws IOException {
        stubCategory();
        stubInsertAssignsId();

        runImport(List.of(
                row("手机A", "SKU-A-001", "199.00", "10"),
                row("手机A", "SKU-A-002", "299.00", "20"),
                row("手机A", "SKU-A-003", "399.00", "30")));

        assertEquals(3, context.getSuccessRows());
        assertEquals(0, context.getFailRows(), "文件内同名是「一个 SPU 三个 SKU」的正常写法");
        verify(productMapper).insertBatch(any());
    }

    @Test
    void importProducts_should_report_blank_product_name() throws IOException {
        stubCategory();

        runImport(List.of(row("", "SKU-A-001", "199.00", "10")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("商品名称不能为空"));
    }

    @Test
    void importProducts_should_reject_price_with_too_many_decimals() throws IOException {
        stubCategory();

        runImport(List.of(row("手机A", "SKU-A-001", "199.999", "10")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("2 位小数"));
    }

    /**
     * 落库批失败后要降级为「逐组重试」，把失败精确落到那一组，
     * 而不是让整批（连同本来没问题的商品）一起失败
     */
    @Test
    void importProducts_should_retry_group_by_group_after_batch_failure() throws IOException {
        stubCategory();
        when(productSkuMapper.insertBatch(any())).thenReturn(1);
        // 第一批（两个商品）整体失败 → 逐组重试时第一组也失败、第二组成功
        doThrow(new RuntimeException("uk_sku_code 冲突"))
                .doThrow(new RuntimeException("uk_sku_code 冲突"))
                .doAnswer(invocation -> assignProductIds(invocation.getArgument(0)))
                .when(productMapper).insertBatch(any());

        runImport(List.of(
                row("商品甲", "SKU-RETRY-1", "10.00", "1"),
                row("商品乙", "SKU-RETRY-2", "20.00", "1")));

        assertEquals(1, context.getSuccessRows(), "重试成功的那一组应计入成功");
        assertEquals(1, context.getFailRows(), "失败的那一组应精确失败");
        assertTrue(errors().get(0).getMessage().contains("落库失败"));
    }

    /**
     * 数据行数超过 {@code excel-task.max-import-rows} 时，任务应整体失败并给出可读原因，
     * 而不是悄悄截断（截断会让运营以为全导进去了）
     */
    @Test
    void importProducts_should_fail_when_rows_exceed_limit() throws IOException {
        // 行数超限在「解析阶段」就中断，压根走不到查分类，所以这里不桩分类
        properties.setMaxImportRows(2);

        BusinessException ex = assertThrows(BusinessException.class, () -> runImport(List.of(
                row("手机A", "SKU-L-1", "10.00", "1"),
                row("手机A", "SKU-L-2", "10.00", "1"),
                row("手机A", "SKU-L-3", "10.00", "1"))));

        assertEquals(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("上限"), "实际: " + ex.getMessage());
    }

    @Test
    void submitImport_should_reject_empty_file() {
        MultipartFile empty = new MockMultipartFile("file", "商品.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.submitImport(empty, 1L));

        assertEquals(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void submitImport_should_reject_non_excel_extension() throws IOException {
        byte[] content = ExcelSupport.writeDynamic("sheet", HEADERS,
                List.of(row("手机A", "SKU-A-001", "199.00", "10")));
        MultipartFile file = new MockMultipartFile("file", "商品.txt", "text/plain", content);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.submitImport(file, 1L));

        assertEquals(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("xlsx"));
    }

    @Test
    void submitImport_should_handOverToExecutorWithBizType() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "商品.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ExcelSupport.writeDynamic("sheet", HEADERS, List.of(row("手机A", "SKU-A-001", "199.00", "10"))));

        importService.submitImport(file, 7L);

        verify(excelTaskExecutor).submitImport(eq("PRODUCT_IMPORT"), eq("商品导入"), eq(7L), eq(file), any());
    }

    @Test
    void buildTemplate_should_produce_parseable_workbook_with_multi_sku_sample() throws IOException {
        byte[] template = importService.buildTemplate();

        var rows = ExcelSupport.readAll(new ByteArrayInputStream(template), ExcelReadOptions.defaults());

        // 模板用两行同名商品演示「一个 SPU 两个 SKU」
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).cell(0), rows.get(1).cell(0));
    }

    // ---------- 测试辅助 ----------

    /**
     * 把给定数据行写成 xlsx 落到临时目录，再构造任务上下文驱动执行体
     */
    private void runImport(List<List<String>> rows) throws IOException {
        byte[] content = ExcelSupport.writeDynamic("商品导入模板", HEADERS, rows);
        Path source = tmpDir.resolve("import-" + System.nanoTime() + ".xlsx");
        Files.write(source, content);

        ExcelTask task = new ExcelTask();
        task.setTaskNo(TASK_NO);
        task.setBizType("PRODUCT_IMPORT");
        task.setFileName("商品.xlsx");
        task.setFilePath(source.toString());
        task.setTaskType(ExcelTaskType.IMPORT.getCode());
        task.setStatus(ExcelTaskStatus.RUNNING.getCode());
        task.setCreatedBy(1L);

        context = new ExcelTaskContext(task, properties, taskService,
                new ExcelFileStorage(properties), new ObjectMapper());
        importService.processImport(context);
        // 执行体只负责攒批，真正刷库由调度器在收尾时做；测试里手动触发一次以观察失败明细
        context.flush();
    }

    /**
     * 汇总本次任务落库的失败明细
     */
    @SuppressWarnings("unchecked")
    private List<ExcelTaskError> errors() {
        ArgumentCaptor<List<ExcelTaskError>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskService, atLeastOnce()).saveErrors(eq(TASK_NO), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Product>> productListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ProductSku>> skuListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private void stubCategory() {
        ProductCategory category = new ProductCategory();
        category.setId(5L);
        category.setName(CATEGORY_NAME);
        when(categoryMapper.selectList(any())).thenReturn(List.of(category));
    }

    /**
     * 模拟 {@code insertBatch} 的自增主键回填（真实实现靠
     * {@code @Options(useGeneratedKeys = true)} 完成）
     */
    private void stubInsertAssignsId() {
        when(productMapper.insertBatch(any())).thenAnswer(invocation -> assignProductIds(invocation.getArgument(0)));
        when(productSkuMapper.insertBatch(any())).thenReturn(1);
    }

    private int assignProductIds(List<Product> products) {
        long nextId = 100L;
        for (Product product : products) {
            product.setId(nextId++);
        }
        return products.size();
    }

    private List<String> row(String productName, String skuCode, String price, String stock) {
        return List.of(productName, "副标题", "https://example.com/a.jpg", CATEGORY_NAME,
                skuCode, "颜色:黑", price, "", stock, "1");
    }

    private List<String> rowWithCategory(String productName, String categoryName,
                                         String skuCode, String price, String stock) {
        return List.of(productName, "副标题", "https://example.com/a.jpg", categoryName,
                skuCode, "颜色:黑", price, "", stock, "1");
    }
}
