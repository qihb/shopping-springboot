package com.springshop.product.product.service.impl;

import com.springshop.common.excel.ExcelReadException;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.excel.ImportError;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductImportService;
import com.springshop.product.product.vo.ProductImportResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品批量导入服务实现
 *
 * <p><b>模板结构</b>：一行一个 SKU，商品级字段（名称 / 副标题 / 主图 / 分类）在同一个商品的
 * 多行里重复填写；解析时按「商品名称」聚合，同名多行合并为一个 SPU + 多个 SKU。
 * 这样运营既能一次导入单 SKU 商品，也能一次导入多规格商品，不需要嵌套结构。
 *
 * <p><b>校验顺序</b>：先做组级校验（商品名、分类），再做行级校验（SKU 字段）。
 * 组级失败会让该商品的所有行一起失败——因为缺少分类的商品没有任何一行能落库。
 */
@Service
public class ProductImportServiceImpl implements ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportServiceImpl.class);

    /** 单次导入的数据行数上限（一行一个 SKU） */
    private static final int MAX_IMPORT_ROWS = ExcelSupport.MAX_ROWS;

    /** 模板表头，列顺序与下面的列下标常量一一对应 */
    private static final List<String> HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    /** 模板示例行：两行同名商品，演示「一个 SPU 两个 SKU」的写法 */
    private static final List<List<String>> SAMPLE_ROWS = List.of(
            List.of("示例商品A", "示例副标题", "https://example.com/a.jpg", "手机",
                    "SKU-A-001", "颜色:黑", "199.00", "299.00", "100", "1"),
            List.of("示例商品A", "示例副标题", "https://example.com/a.jpg", "手机",
                    "SKU-A-002", "颜色:白", "199.00", "299.00", "80", "1"));

    private static final int COL_PRODUCT_NAME = 0;

    private static final int COL_SUBTITLE = 1;

    private static final int COL_MAIN_IMAGE = 2;

    private static final int COL_CATEGORY_NAME = 3;

    private static final int COL_SKU_CODE = 4;

    private static final int COL_SPECS = 5;

    private static final int COL_PRICE = 6;

    private static final int COL_ORIGINAL_PRICE = 7;

    private static final int COL_STOCK = 8;

    private static final int COL_STATUS = 9;

    /** 长度上限与 product / product_sku 表结构保持一致，避免超长值在写库阶段才报错 */
    private static final int MAX_PRODUCT_NAME_LEN = 100;

    private static final int MAX_SUBTITLE_LEN = 200;

    private static final int MAX_IMAGE_URL_LEN = 255;

    private static final int MAX_SKU_CODE_LEN = 64;

    private static final int MAX_SPECS_LEN = 255;

    /** DECIMAL(10,2)：整数部分最多 8 位、小数最多 2 位 */
    private static final int MAX_PRICE_INTEGER_DIGITS = 8;

    private static final int MAX_PRICE_SCALE = 2;

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductCategoryMapper categoryMapper;

    public ProductImportServiceImpl(ProductMapper productMapper,
                                    ProductSkuMapper productSkuMapper,
                                    ProductCategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductImportResultVO importProducts(MultipartFile file) {
        List<ExcelRow> rows = readRows(file);

        // 预加载分类与已占用的 SKU 编码，避免逐行查库（N+1）
        Map<String, List<ProductCategory>> categoriesByName = loadCategoriesByName();
        Set<String> occupiedSkuCodes = loadOccupiedSkuCodes(rows);

        List<ImportError> errors = new ArrayList<>();
        Map<String, List<ExcelRow>> groups = groupByProductName(rows, errors);
        Set<String> importedSkuCodes = new HashSet<>();
        int productCount = 0;
        int skuCount = 0;

        for (Map.Entry<String, List<ExcelRow>> group : groups.entrySet()) {
            int createdSkus = importGroup(group.getKey(), group.getValue(),
                    categoriesByName, occupiedSkuCodes, importedSkuCodes, errors);
            if (createdSkus > 0) {
                productCount++;
                skuCount += createdSkus;
            }
        }

        ProductImportResultVO result = new ProductImportResultVO();
        result.setTotalRows(rows.size());
        result.setProductCount(productCount);
        result.setSkuCount(skuCount);
        result.setFailRowCount(errors.size());
        result.setErrors(errors);
        log.info("商品导入完成：共 {} 行，成功商品 {} 个 / SKU {} 个，失败 {} 行",
                rows.size(), productCount, skuCount, errors.size());
        return result;
    }

    @Override
    public byte[] buildTemplate() {
        try {
            return ExcelSupport.write("商品导入模板", HEADERS, SAMPLE_ROWS);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "模板生成失败，请稍后重试");
        }
    }

    /**
     * 导入单个商品：组级校验 → 行级校验 → 落库 SPU + SKU
     *
     * @return 成功创建的 SKU 数量；0 表示该商品未创建
     */
    private int importGroup(String productName, List<ExcelRow> groupRows,
                            Map<String, List<ProductCategory>> categoriesByName,
                            Set<String> occupiedSkuCodes, Set<String> importedSkuCodes,
                            List<ImportError> errors) {
        Long categoryId = resolveCategoryId(productName, groupRows, categoriesByName, errors);
        if (categoryId == null) {
            return 0;
        }
        String subtitle = firstNonBlank(groupRows, COL_SUBTITLE);
        String mainImage = firstNonBlank(groupRows, COL_MAIN_IMAGE);
        String lengthError = checkGroupTextLength(subtitle, mainImage);
        if (lengthError != null) {
            addGroupError(groupRows, errors, lengthError);
            return 0;
        }

        // 先把组内所有合法行构建成 SKU，再决定是否创建 SPU：
        // 避免出现「SPU 已创建但一个 SKU 都没有」的空商品
        List<ProductSku> skus = new ArrayList<>();
        for (ExcelRow row : groupRows) {
            try {
                skus.add(buildSku(row, occupiedSkuCodes, importedSkuCodes));
            } catch (IllegalArgumentException e) {
                errors.add(new ImportError(row.getRowNum(), e.getMessage()));
            }
        }
        if (skus.isEmpty()) {
            return 0;
        }

        Product product = new Product();
        product.setCategoryId(categoryId);
        product.setName(productName);
        product.setSubtitle(subtitle);
        product.setMainImage(mainImage);
        product.setSales(0);
        // 商品上架状态取组内第一条成功导入的 SKU 的状态，模板中已注明「同一商品以第一行为准」
        product.setStatus(skus.get(0).getStatus());
        productMapper.insert(product);

        for (ProductSku sku : skus) {
            sku.setProductId(product.getId());
            productSkuMapper.insert(sku);
        }
        return skus.size();
    }

    /**
     * 解析分类名称对应的分类 id；无法唯一定位时把该商品的所有行标记为失败
     */
    private Long resolveCategoryId(String productName, List<ExcelRow> groupRows,
                                   Map<String, List<ProductCategory>> categoriesByName,
                                   List<ImportError> errors) {
        String categoryName = firstNonBlank(groupRows, COL_CATEGORY_NAME);
        if (categoryName == null) {
            addGroupError(groupRows, errors, "分类名称不能为空");
            return null;
        }
        List<ProductCategory> matched = categoriesByName.get(categoryName);
        if (matched == null || matched.isEmpty()) {
            addGroupError(groupRows, errors, "分类「" + categoryName + "」不存在");
            return null;
        }
        if (matched.size() > 1) {
            // 同名分类无法判断该挂到哪一个，宁可让运营先改名，也不要猜错
            addGroupError(groupRows, errors, "分类名称「" + categoryName + "」存在多个同名分类，请先在分类管理中区分命名");
            return null;
        }
        log.debug("商品「{}」匹配到分类 id={}", productName, matched.get(0).getId());
        return matched.get(0).getId();
    }

    private String checkGroupTextLength(String subtitle, String mainImage) {
        if (subtitle != null && subtitle.length() > MAX_SUBTITLE_LEN) {
            return "副标题长度不能超过 " + MAX_SUBTITLE_LEN + " 位";
        }
        if (mainImage != null && mainImage.length() > MAX_IMAGE_URL_LEN) {
            return "主图 URL 长度不能超过 " + MAX_IMAGE_URL_LEN + " 位";
        }
        return null;
    }

    /**
     * 构建单个 SKU；字段不合法时抛出 {@link IllegalArgumentException}，由调用方收集为该行的错误
     */
    private ProductSku buildSku(ExcelRow row, Set<String> occupiedSkuCodes, Set<String> importedSkuCodes) {
        String skuCode = row.cell(COL_SKU_CODE);
        if (ExcelSupport.isBlankText(skuCode)) {
            throw new IllegalArgumentException("SKU 编码不能为空");
        }
        if (skuCode.length() > MAX_SKU_CODE_LEN) {
            throw new IllegalArgumentException("SKU 编码长度不能超过 " + MAX_SKU_CODE_LEN + " 位");
        }
        String specs = row.cell(COL_SPECS);
        if (specs.length() > MAX_SPECS_LEN) {
            throw new IllegalArgumentException("规格长度不能超过 " + MAX_SPECS_LEN + " 位");
        }

        BigDecimal price = parsePrice(row.cell(COL_PRICE), "销售价");
        BigDecimal originalPrice = parseOptionalPrice(row.cell(COL_ORIGINAL_PRICE), "原价");
        Integer stock = parseStock(row.cell(COL_STOCK));
        Integer status = parseStatus(row.cell(COL_STATUS));

        // 所有字段都校验通过后才占用 SKU 编码：
        // 否则「因价格写错而失败的行」会把编码锁死，后面同编码的合法行被误报为重复
        if (occupiedSkuCodes.contains(skuCode) || !importedSkuCodes.add(skuCode)) {
            throw new IllegalArgumentException("SKU 编码「" + skuCode + "」已存在");
        }

        ProductSku sku = new ProductSku();
        sku.setSkuCode(skuCode);
        sku.setSpecs(ExcelSupport.isBlankText(specs) ? null : specs);
        sku.setPrice(price);
        sku.setOriginalPrice(originalPrice);
        sku.setStock(stock);
        sku.setStatus(status);
        return sku;
    }

    private BigDecimal parsePrice(String text, String fieldName) {
        BigDecimal price = ExcelSupport.parseDecimal(text);
        if (price == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        checkPriceScale(price, fieldName);
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + "必须大于 0");
        }
        return price;
    }

    private BigDecimal parseOptionalPrice(String text, String fieldName) {
        BigDecimal price = ExcelSupport.parseDecimal(text);
        if (price == null) {
            return null;
        }
        checkPriceScale(price, fieldName);
        if (price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + "不能为负数");
        }
        return price;
    }

    /**
     * 价格精度校验：DECIMAL(10,2) 会静默四舍五入，价格上不能接受这种「悄悄改数」
     */
    private void checkPriceScale(BigDecimal price, String fieldName) {
        if (price.scale() > MAX_PRICE_SCALE
                || price.precision() - price.scale() > MAX_PRICE_INTEGER_DIGITS) {
            throw new IllegalArgumentException(fieldName + "最多 8 位整数、2 位小数");
        }
    }

    private Integer parseStock(String text) {
        if (ExcelSupport.isBlankText(text)) {
            return 0;
        }
        Integer stock = ExcelSupport.parseInt(text);
        if (stock < 0) {
            throw new IllegalArgumentException("库存不能为负数");
        }
        return stock;
    }

    private Integer parseStatus(String text) {
        if (ExcelSupport.isBlankText(text)) {
            return 1;
        }
        Integer status = ExcelSupport.parseInt(text);
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("状态只能是 1（上架）或 0（下架）");
        }
        return status;
    }

    /**
     * 按商品名称聚合；名称为空或超长的行直接记为失败，不进入分组
     */
    private Map<String, List<ExcelRow>> groupByProductName(List<ExcelRow> rows, List<ImportError> errors) {
        Map<String, List<ExcelRow>> groups = new LinkedHashMap<>();
        for (ExcelRow row : rows) {
            String name = row.cell(COL_PRODUCT_NAME);
            if (ExcelSupport.isBlankText(name)) {
                errors.add(new ImportError(row.getRowNum(), "商品名称不能为空"));
                continue;
            }
            if (name.length() > MAX_PRODUCT_NAME_LEN) {
                errors.add(new ImportError(row.getRowNum(),
                        "商品名称长度不能超过 " + MAX_PRODUCT_NAME_LEN + " 位"));
                continue;
            }
            groups.computeIfAbsent(name, key -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    /**
     * 取组内首个非空值作为商品级字段（副标题 / 主图 / 分类）
     */
    private String firstNonBlank(List<ExcelRow> groupRows, int columnIndex) {
        for (ExcelRow row : groupRows) {
            String value = row.cell(columnIndex);
            if (!ExcelSupport.isBlankText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 组级失败：该商品下的每一行都要给出原因，否则运营看不到问题出在哪一行
     */
    private void addGroupError(List<ExcelRow> groupRows, List<ImportError> errors, String message) {
        for (ExcelRow row : groupRows) {
            errors.add(new ImportError(row.getRowNum(), message));
        }
    }

    private Map<String, List<ProductCategory>> loadCategoriesByName() {
        return categoryMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(ProductCategory::getName));
    }

    /**
     * 批量查询已被占用的 SKU 编码（与唯一索引口径一致，含逻辑删除行）
     */
    private Set<String> loadOccupiedSkuCodes(List<ExcelRow> rows) {
        Set<String> skuCodes = rows.stream()
                .map(row -> row.cell(COL_SKU_CODE))
                .filter(code -> !ExcelSupport.isBlankText(code))
                .collect(Collectors.toSet());
        if (skuCodes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(productSkuMapper.selectOccupiedSkuCodes(skuCodes));
    }

    private List<ExcelRow> readRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), "请选择要导入的文件");
        }
        if (!hasExcelExtension(file.getOriginalFilename())) {
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), "仅支持 .xlsx / .xls 格式");
        }
        try (InputStream in = file.getInputStream()) {
            return ExcelSupport.read(in, MAX_IMPORT_ROWS);
        } catch (ExcelReadException e) {
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), e.getMessage());
        } catch (IOException e) {
            log.warn("读取商品导入文件失败", e);
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(),
                    "文件读取失败，请重新导出后重试");
        }
    }

    private boolean hasExcelExtension(String fileName) {
        if (ExcelSupport.isBlankText(fileName)) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }
}
