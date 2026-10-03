package com.springshop.product.product.service.impl;

import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.vo.ProductImportResultVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品批量导入服务单元测试
 *
 * <p>测试数据用 {@link ExcelSupport#write} 真实生成 xlsx，覆盖「POI 解析 → 分组 → 校验 → 落库」全链路。
 */
@ExtendWith(MockitoExtension.class)
class ProductImportServiceImplTest {

    private static final List<String> HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    private static final String CATEGORY_NAME = "手机";

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductCategoryMapper categoryMapper;

    private ProductImportServiceImpl importService;

    @BeforeEach
    void setUp() {
        importService = new ProductImportServiceImpl(productMapper, productSkuMapper, categoryMapper);
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

        MultipartFile file = excelFile(List.of(
                row("手机A", "SKU-A-001", "199.00", "10"),
                row("手机A", "SKU-A-002", "299.00", "20")));

        ProductImportResultVO result = importService.importProducts(file);

        assertEquals(2, result.getTotalRows());
        assertEquals(1, result.getProductCount());
        assertEquals(2, result.getSkuCount());
        assertEquals(0, result.getFailRowCount());

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(productCaptor.capture());
        assertEquals("手机A", productCaptor.getValue().getName());
        assertEquals(5L, productCaptor.getValue().getCategoryId());
        assertEquals(0, productCaptor.getValue().getSales().intValue());

        ArgumentCaptor<ProductSku> skuCaptor = ArgumentCaptor.forClass(ProductSku.class);
        verify(productSkuMapper, times(2)).insert(skuCaptor.capture());
        assertTrue(skuCaptor.getAllValues().stream().allMatch(sku -> sku.getProductId().equals(100L)));
    }

    @Test
    void importProducts_should_fail_whole_group_when_category_not_found() throws IOException {
        stubCategory();

        MultipartFile file = excelFile(List.of(
                rowWithCategory("手机A", "不存在分类", "SKU-A-001", "199.00", "10"),
                rowWithCategory("手机A", "不存在分类", "SKU-A-002", "199.00", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        // 缺分类的商品没有任何一行能落库，因此整组一起失败并逐行给出原因
        assertEquals(0, result.getProductCount());
        assertEquals(2, result.getFailRowCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("不存在"));
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void importProducts_should_fail_group_when_all_sku_rows_invalid() throws IOException {
        stubCategory();

        MultipartFile file = excelFile(List.of(
                row("手机A", "", "199.00", "10"),
                row("手机A", "", "199.00", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        // 避免产生「没有 SKU 的空商品」
        assertEquals(0, result.getProductCount());
        assertEquals(2, result.getFailRowCount());
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void importProducts_should_skip_invalid_row_but_keep_others() throws IOException {
        stubCategory();
        stubInsertAssignsId();

        MultipartFile file = excelFile(List.of(
                row("手机A", "SKU-A-001", "199.00", "10"),
                // 价格不是数字 → 该行失败
                row("手机A", "SKU-A-002", "abc", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        assertEquals(1, result.getProductCount());
        assertEquals(1, result.getSkuCount());
        assertEquals(1, result.getFailRowCount());
        assertEquals(3, result.getErrors().get(0).getRowNum());
        assertTrue(result.getErrors().get(0).getMessage().contains("合法数字"));
    }

    @Test
    void importProducts_should_report_duplicate_sku_code_inside_file() throws IOException {
        stubCategory();
        stubInsertAssignsId();

        MultipartFile file = excelFile(List.of(
                row("手机A", "SKU-DUP", "199.00", "10"),
                row("手机B", "SKU-DUP", "299.00", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        assertEquals(1, result.getProductCount());
        assertEquals(1, result.getSkuCount());
        assertEquals(1, result.getFailRowCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("SKU-DUP"));
    }

    @Test
    void importProducts_should_report_sku_code_occupied_in_db() throws IOException {
        stubCategory();
        // 编码查重与 uk_sku_code 口径一致：逻辑删除的 SKU 仍然占用编码
        when(productSkuMapper.selectOccupiedSkuCodes(any())).thenReturn(List.of("SKU-A-001"));

        MultipartFile file = excelFile(List.of(row("手机A", "SKU-A-001", "199.00", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        assertEquals(0, result.getProductCount());
        assertEquals(1, result.getFailRowCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("已存在"));
    }

    @Test
    void importProducts_should_report_blank_product_name() throws IOException {
        stubCategory();

        MultipartFile file = excelFile(List.of(
                row("", "SKU-A-001", "199.00", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        assertEquals(0, result.getProductCount());
        assertEquals(1, result.getFailRowCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("商品名称不能为空"));
    }

    @Test
    void importProducts_should_reject_price_with_too_many_decimals() throws IOException {
        stubCategory();

        MultipartFile file = excelFile(List.of(row("手机A", "SKU-A-001", "199.999", "10")));

        ProductImportResultVO result = importService.importProducts(file);

        assertEquals(0, result.getProductCount());
        assertEquals(1, result.getFailRowCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("2 位小数"));
    }

    @Test
    void importProducts_should_reject_empty_file() {
        MultipartFile empty = new MockMultipartFile("file", "商品.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.importProducts(empty));

        assertEquals(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void importProducts_should_reject_non_excel_extension() throws IOException {
        byte[] content = ExcelSupport.write("sheet", HEADERS,
                List.of(row("手机A", "SKU-A-001", "199.00", "10")));
        MultipartFile file = new MockMultipartFile("file", "商品.txt", "text/plain", content);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.importProducts(file));

        assertEquals(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("xlsx"));
    }

    @Test
    void buildTemplate_should_produce_parseable_workbook_with_multi_sku_sample() throws IOException {
        byte[] template = importService.buildTemplate();

        List<ExcelRow> rows = ExcelSupport.read(new ByteArrayInputStream(template), 10);

        // 模板用两行同名商品演示「一个 SPU 两个 SKU」
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).cell(0), rows.get(1).cell(0));
    }

    private void stubCategory() {
        ProductCategory category = new ProductCategory();
        category.setId(5L);
        category.setName(CATEGORY_NAME);
        when(categoryMapper.selectList(any())).thenReturn(List.of(category));
    }

    private void stubInsertAssignsId() {
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(100L);
            return 1;
        });
        when(productSkuMapper.insert(any(ProductSku.class))).thenReturn(1);
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

    private MultipartFile excelFile(List<List<String>> rows) throws IOException {
        return new MockMultipartFile("file", "商品.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ExcelSupport.write("sheet", HEADERS, rows));
    }
}
