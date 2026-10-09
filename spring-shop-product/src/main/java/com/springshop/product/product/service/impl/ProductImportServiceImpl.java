package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.excel.ExcelFileType;
import com.springshop.common.excel.ExcelReadException;
import com.springshop.common.excel.ExcelReadOptions;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.excel.ImportError;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.OccupiedProductSpec;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import com.springshop.product.product.service.ProductImportService;
import com.springshop.product.product.support.SkuSpecNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品批量导入服务实现
 *
 * <p><b>模板结构</b>：一行一个 SKU，商品级字段（名称 / 副标题 / 主图 / 分类）在同一个商品的
 * 多行里重复填写；解析时按「商品名称」聚合，同名多行合并为一个 SPU + 多个 SKU。
 *
 * <p><b>校验顺序</b>：先做组级校验（分类、商品级字段长度），再做行级校验（SKU 字段）。
 * 组级失败会让该商品的所有行一起失败——因为缺少分类的商品没有任何一行能落库。
 *
 * <p><b>唯一性按 {@code (商品名称, 规格)} 逐行判定（2026-10-09 起）</b>：
 * <ul>
 *   <li><b>名称在库里不存在</b> → 新建 SPU，本组所有合法行作为它的 SKU；</li>
 *   <li><b>名称已存在 + 规格不同</b> → <b>复用现有 {@code productId}，只追加 SKU，不新建 SPU</b>
 *       （这正是「同名不同规格 = 同一 SPU 下两个 SKU」在导入侧的落地）；</li>
 *   <li><b>名称已存在 + 规格相同</b> → 只拒绝<b>这一行</b>，同组其他规格的行照常导入。</li>
 * </ul>
 * 规格一律过 {@link SkuSpecNormalizer} 再比，否则段序 / 全角符号 / 空格都能把重复绕过去。
 *
 * <p><b>为什么不沿用「按名称整组拒绝」</b>：{@code product.name} 上没有唯一索引，
 * 「同名 + 换一批 SKU 编码」的重传确实会静默建出第二个同名 SPU（运营看到两个一模一样的
 * 商品，订单 / 购物车 / 统计各挂一个，事后无法合并）。但整组拒绝把「给已有商品补一个规格」
 * 这个完全正当的诉求也一并堵死了。现在改为按 {@code (名称, 规格)} 逐行判定，
 * 既拦住真正的重复，又放行合法的追加。
 *
 * <p><b>复用 SPU 时商品级字段（分类 / 副标题 / 主图）一律忽略</b>：这三列描述的是 SPU，
 * 而本次只往既有 SPU 追加 SKU、不去改它。所以复用分支既不解析分类、也不写这三个字段 ——
 * 顺手写入等于「悄悄改了别人的商品」，比忽略更危险。
 *
 * <p>这是应用层的兜底校验，不是数据库约束：查重与落库之间仍有极窄的窗口，
 * 两个导入任务同时导入同一个新商品名时仍可能各建一个 SPU。要真正堵死需要给
 * {@code product.name} 加唯一索引（会与逻辑删除冲突，且需要先清理历史重名数据），
 * 属于独立的库表变更，不在这里顺手做。
 *
 * <p><b>为什么执行体不整体包一个事务</b>：上万行的导入如果放在一个事务里，
 * 要么全成功要么全失败，且长事务会长时间持有锁与 undo log。这里改成
 * 「按批开事务、批内原子、批间独立」，配合「部分成功」语义：
 * 某一批因为并发撞唯一键失败时，只回滚那一批并逐组重试，把失败精确落到组上，
 * 前面已成功的批次不受影响。
 *
 * <p><b>内存特性</b>：解析走 Fesod 的 SAX 事件流（不构建整表 DOM），但分组需要跨行聚合，
 * 因此数据行会驻留内存（上限 {@code excel.task.max-import-rows}，默认 10 万行）。
 * 十万行 ExcelRow 的堆占用约几十 MB，是「按名称聚合」这个语义换来的必要代价。
 */
@Service
public class ProductImportServiceImpl implements ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportServiceImpl.class);

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

    /**
     * 批量查重的分片大小（SKU 编码查重、商品名称查重共用）
     *
     * <p>一次性把 10 万个值塞进 {@code IN (...)} 会超出 MySQL 的
     * {@code max_allowed_packet} 与占位符上限，必须分片查。
     */
    private static final int QUERY_CHUNK = 1000;

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductCategoryMapper categoryMapper;
    private final InventoryService inventoryService;
    private final ExcelTaskExecutor excelTaskExecutor;
    private final ExcelTaskProperties excelTaskProperties;
    private final TransactionTemplate transactionTemplate;

    public ProductImportServiceImpl(ProductMapper productMapper,
                                    ProductSkuMapper productSkuMapper,
                                    ProductCategoryMapper categoryMapper,
                                    InventoryService inventoryService,
                                    ExcelTaskExecutor excelTaskExecutor,
                                    ExcelTaskProperties excelTaskProperties,
                                    PlatformTransactionManager transactionManager) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.categoryMapper = categoryMapper;
        this.inventoryService = inventoryService;
        this.excelTaskExecutor = excelTaskExecutor;
        this.excelTaskProperties = excelTaskProperties;
        // 用 TransactionTemplate 而不是 @Transactional：执行体是异步线程里的一次普通方法调用，
        // 自调用不会走代理、@Transactional 不会生效；而且这里需要「批内事务」这种精细控制
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ExcelTaskVO submitImport(MultipartFile file, Long adminId) {
        validateFile(file);
        return excelTaskExecutor.submitImport(BIZ_TYPE, BIZ_NAME, adminId, file, this::processImport);
    }

    @Override
    public byte[] buildTemplate() {
        try {
            return ExcelSupport.writeDynamic("商品导入模板", HEADERS, SAMPLE_ROWS);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "模板生成失败，请稍后重试");
        }
    }

    /**
     * 后台线程执行体：解析 → 校验分组 → 分批落库 → 上报进度
     *
     * <p>包级可见（非 private）是为了让单元测试能直接驱动执行体，
     * 不必真的起线程池、也不必等异步任务跑完再断言。
     */
    void processImport(ExcelTaskContext context) {
        List<ExcelRow> rows = readRows(context);
        Map<String, List<ProductCategory>> categoriesByName = loadCategoriesByName();
        Set<String> occupiedSkuCodes = loadOccupiedSkuCodes(rows);
        Map<String, ExistingProduct> existingProducts = loadExistingProducts(rows);

        // 按名称聚合；名称为空或超长的行在这里就被记为失败，不进入分组
        List<ImportError> groupErrors = new ArrayList<>();
        Map<String, List<ExcelRow>> groups = groupByProductName(rows, groupErrors);

        int ungroupedRows = rows.size() - groups.values().stream().mapToInt(List::size).sum();
        ImportRecorder recorder = new ImportRecorder(context, ungroupedRows);
        for (ImportError error : groupErrors) {
            recorder.error(error.getRowNum(), error.getMessage());
        }

        Set<String> importedSkuCodes = new HashSet<>();
        List<ProductGroup> pending = new ArrayList<>();
        int batchSize = excelTaskProperties.getImportBatchSize();

        for (Map.Entry<String, List<ExcelRow>> entry : groups.entrySet()) {
            List<ExcelRow> groupRows = entry.getValue();
            ProductGroup group = buildGroup(entry.getKey(), groupRows, categoriesByName,
                    existingProducts, occupiedSkuCodes, importedSkuCodes, recorder);
            recorder.advance(groupRows.size());
            if (group != null) {
                pending.add(group);
            }
            if (pending.size() >= batchSize) {
                flushBatch(pending, importedSkuCodes, recorder);
                pending.clear();
            }
            recorder.flush();
        }
        if (!pending.isEmpty()) {
            flushBatch(pending, importedSkuCodes, recorder);
        }
        // 无条件收尾上报：一个「全部行都因为商品名为空而失败」的文件不会产生任何分组，
        // 如果只在有分组时才上报，任务就会以「处理 0 行 / 失败 0 行」收场——
        // 用户看到的是「什么都没发生」，而失败明细里明明有内容
        recorder.flush();

        log.info("商品导入完成 taskNo={} 共 {} 行，成功 SKU {} 个，失败 {} 行",
                context.getTaskNo(), rows.size(), recorder.successSkus(), recorder.failRows());
    }

    /**
     * 落一批商品：批内一个事务，成功则累计成功数，失败则逐组重试并把失败精确落到组
     */
    private void flushBatch(List<ProductGroup> batch, Set<String> importedSkuCodes, ImportRecorder recorder) {
        List<ProductGroup> current = List.copyOf(batch);
        try {
            transactionTemplate.executeWithoutResult(status -> persistGroups(current));
            recorder.successSkus(current.stream().mapToInt(group -> group.skus().size()).sum());
            return;
        } catch (Exception e) {
            log.warn("商品导入批落库失败，降级为逐组重试，批大小={}", current.size(), e);
        }
        // 整批回滚后逐组重试：能定位到具体是哪一组撞了唯一键，其余组仍然能导入成功
        for (ProductGroup group : current) {
            try {
                transactionTemplate.executeWithoutResult(status -> persistGroups(List.of(group)));
                recorder.successSkus(group.skus().size());
            } catch (Exception e) {
                // 这一组没落库，它占用的 SKU 编码要释放，否则后续同编码的合法行会被误判为重复
                group.skus().forEach(draft -> importedSkuCodes.remove(draft.sku().getSkuCode()));
                recorder.groupError(group.rows(), "落库失败：" + rootMessage(e));
            }
        }
    }

    /**
     * 落一批商品：批内一个事务
     *
     * <p>复用既有 SPU 的组不参与 {@code insertBatch(products)}，只把 SKU 挂到已有的
     * {@code productId} 上 —— 这正是「同名不同规格只追加 SKU」的落地点。
     */
    private void persistGroups(List<ProductGroup> groups) {
        List<Product> newProducts = groups.stream()
                .filter(group -> group.product() != null)
                .map(ProductGroup::product)
                .toList();
        if (!newProducts.isEmpty()) {
            productMapper.insertBatch(newProducts);
        }

        List<ProductSku> skus = new ArrayList<>();
        // SKU 表自 V10 起已无库存字段（迁移期镜像列已删），所以每行想要的目标库存
        // 不能再挂在实体上，改为单独带着走，落库后建库存行时按 sku_code 取回
        Map<String, Integer> stockBySkuCode = new HashMap<>();
        for (ProductGroup group : groups) {
            // 新建组用 insertBatch 回填的自增主键；复用组用库里既有的 productId
            Long productId = group.product() != null ? group.product().getId() : group.existingProductId();
            for (SkuDraft draft : group.skus()) {
                ProductSku sku = draft.sku();
                sku.setProductId(productId);
                skus.add(sku);
                stockBySkuCode.put(sku.getSkuCode(), draft.stock());
            }
        }
        productSkuMapper.insertBatch(skus);

        // 库存以 inventory 表为准。SKU 的批量插入是自定义 @Insert，<b>不会回填自增主键</b>，
        // 所以按 sku_code 回查一次拿到 id，再逐个建库存行（initStock 同时会落一条初始化流水）
        List<String> skuCodes = skus.stream().map(ProductSku::getSkuCode).toList();
        Map<String, Long> skuIdByCode = productSkuMapper.selectList(
                        new LambdaQueryWrapper<ProductSku>().in(ProductSku::getSkuCode, skuCodes))
                .stream()
                .collect(Collectors.toMap(ProductSku::getSkuCode, ProductSku::getId));
        for (ProductSku sku : skus) {
            Long skuId = skuIdByCode.get(sku.getSkuCode());
            if (skuId != null) {
                inventoryService.initStock(skuId, stockBySkuCode.getOrDefault(sku.getSkuCode(), 0));
            }
        }
    }

    /**
     * 构建单个商品：组级校验 → 行级校验 → 组装 SPU 与 SKU（不落库）
     *
     * <p>名称已存在时走「复用」分支：不建 SPU、不碰商品级字段，只把本组里规格尚未被占用的行
     * 作为新 SKU 追加到既有 {@code productId} 上。
     *
     * @return 校验通过的商品；返回 null 表示该组没有任何一行能落库（失败原因已记录）
     */
    private ProductGroup buildGroup(String productName, List<ExcelRow> groupRows,
                                    Map<String, List<ProductCategory>> categoriesByName,
                                    Map<String, ExistingProduct> existingProducts,
                                    Set<String> occupiedSkuCodes, Set<String> importedSkuCodes,
                                    ImportRecorder recorder) {
        ExistingProduct existing = existingProducts.get(productName);
        boolean reuse = existing != null;

        Long categoryId = null;
        String subtitle = null;
        String mainImage = null;
        if (!reuse) {
            // 分类 / 副标题 / 主图都是 SPU 级字段，只有新建 SPU 时才需要（也才有意义）。
            // 复用分支不去改既有 SPU，因此既不校验也不写入 —— 详见类注释
            categoryId = resolveCategoryId(productName, groupRows, categoriesByName, recorder);
            if (categoryId == null) {
                return null;
            }
            subtitle = firstNonBlank(groupRows, COL_SUBTITLE);
            mainImage = firstNonBlank(groupRows, COL_MAIN_IMAGE);
            String lengthError = checkGroupTextLength(subtitle, mainImage);
            if (lengthError != null) {
                recorder.groupError(groupRows, lengthError);
                return null;
            }
        }

        // 该名称下已被占用的规格 = 库里已有的 + 本组前面已经吃下的行。
        // 组是按名称切的，所以这个集合天然只属于这一个名称，不需要跨组共享
        Set<String> claimedSpecs = new HashSet<>();
        if (reuse) {
            claimedSpecs.addAll(existing.occupiedSpecs());
        }

        // 先把组内所有合法行构建成 SKU，再决定是否创建 SPU：
        // 避免出现「SPU 已创建但一个 SKU 都没有」的空商品
        List<SkuDraft> skus = new ArrayList<>();
        for (ExcelRow row : groupRows) {
            try {
                skus.add(buildSku(productName, row, claimedSpecs, occupiedSkuCodes, importedSkuCodes));
            } catch (IllegalArgumentException e) {
                recorder.error(row.getRowNum(), e.getMessage());
            }
        }
        if (skus.isEmpty()) {
            return null;
        }

        if (reuse) {
            return ProductGroup.reuse(existing.productId(), skus, groupRows);
        }

        Product product = new Product();
        product.setCategoryId(categoryId);
        product.setName(productName);
        product.setSubtitle(subtitle);
        product.setMainImage(mainImage);
        product.setSales(0);
        // 商品上架状态取组内第一条成功导入的 SKU 的状态，模板中已注明「同一商品以第一行为准」
        product.setStatus(skus.get(0).sku().getStatus());
        return ProductGroup.create(product, skus, groupRows);
    }

    /**
     * 解析分类名称对应的分类 id；无法唯一定位时把该商品的所有行标记为失败
     */
    private Long resolveCategoryId(String productName, List<ExcelRow> groupRows,
                                   Map<String, List<ProductCategory>> categoriesByName,
                                   ImportRecorder recorder) {
        String categoryName = firstNonBlank(groupRows, COL_CATEGORY_NAME);
        if (categoryName == null) {
            recorder.groupError(groupRows, "分类名称不能为空");
            return null;
        }
        List<ProductCategory> matched = categoriesByName.get(categoryName);
        if (matched == null || matched.isEmpty()) {
            recorder.groupError(groupRows, "分类「" + categoryName + "」不存在");
            return null;
        }
        if (matched.size() > 1) {
            // 同名分类无法判断该挂到哪一个，宁可让运营先改名，也不要猜错
            recorder.groupError(groupRows,
                    "分类名称「" + categoryName + "」存在多个同名分类，请先在分类管理中区分命名");
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
     *
     * <p>返回 {@link SkuDraft}（实体 + 该行想要的目标库存）而不是裸实体 ——
     * SKU 表自 V10 起没有库存字段，库存只能随草稿一起往下传，落库后再建 {@code inventory} 行。
     *
     * @param claimedSpecs 该商品名称下已被占用的<b>规范化规格</b>（库里已有的 + 本组前面已成功的行）
     */
    private SkuDraft buildSku(String productName, ExcelRow row, Set<String> claimedSpecs,
                              Set<String> occupiedSkuCodes, Set<String> importedSkuCodes) {
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

        // 「占用」放在所有纯校验之后：否则「因价格写错而失败的行」会把编码 / 规格锁死，
        // 后面同值的合法行被误报为重复
        String normalizedSpecs = SkuSpecNormalizer.normalize(specs);
        if (occupiedSkuCodes.contains(skuCode) || importedSkuCodes.contains(skuCode)) {
            throw new IllegalArgumentException("SKU 编码「" + skuCode + "」已存在");
        }
        if (claimedSpecs.contains(normalizedSpecs)) {
            throw new IllegalArgumentException("商品「" + productName + "」的规格「"
                    + displaySpecs(specs) + "」已存在，同名同规格不可重复；如要追加规格请换一个规格值");
        }
        importedSkuCodes.add(skuCode);
        claimedSpecs.add(normalizedSpecs);

        ProductSku sku = new ProductSku();
        sku.setSkuCode(skuCode);
        sku.setSpecs(ExcelSupport.isBlankText(specs) ? null : specs);
        sku.setPrice(price);
        sku.setOriginalPrice(originalPrice);
        sku.setStatus(status);
        // 库存不落在实体上：新增时留空视为 0（与 ProductManageServiceImpl.insertSku 同口径）
        return new SkuDraft(sku, stock == null ? 0 : stock);
    }

    /**
     * 规格为空时给一个可读的占位，避免错误提示里出现空白
     */
    private String displaySpecs(String specs) {
        return ExcelSupport.isBlankText(specs) ? "（无规格）" : specs;
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

    private Map<String, List<ProductCategory>> loadCategoriesByName() {
        return categoryMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(ProductCategory::getName));
    }

    /**
     * 批量查询已被占用的 SKU 编码（与唯一索引口径一致，含逻辑删除行）
     *
     * <p>分片查询：一次导入 10 万个编码时，单条 {@code IN (...)} 会超出数据库的
     * 报文大小与占位符上限，必须按 {@link #QUERY_CHUNK} 切片。
     */
    private Set<String> loadOccupiedSkuCodes(List<ExcelRow> rows) {
        Set<String> skuCodes = rows.stream()
                .map(row -> row.cell(COL_SKU_CODE))
                .filter(code -> !ExcelSupport.isBlankText(code))
                .collect(Collectors.toSet());
        if (skuCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> occupied = new HashSet<>();
        List<String> chunk = new ArrayList<>(QUERY_CHUNK);
        for (String code : skuCodes) {
            chunk.add(code);
            if (chunk.size() >= QUERY_CHUNK) {
                occupied.addAll(productSkuMapper.selectOccupiedSkuCodes(chunk));
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            occupied.addAll(productSkuMapper.selectOccupiedSkuCodes(chunk));
        }
        return occupied;
    }

    /**
     * 批量查询本文件里出现过的商品名称在库中是否已存在，以及各自已占用的规格
     *
     * <p>分片查而不是每个分组查一次：一万个商品就是一万次往返，是本场景主要的耗时来源。
     *
     * <p>同名对应多个 {@code productId} 时（历史脏数据）只认第一条：新规则要求
     * 「同名不同规格复用同一个 SPU」，已有两个同名 SPU 时只能任选一个追加，无法自动合并。
     */
    private Map<String, ExistingProduct> loadExistingProducts(List<ExcelRow> rows) {
        Set<String> names = rows.stream()
                .map(row -> row.cell(COL_PRODUCT_NAME))
                .filter(name -> !ExcelSupport.isBlankText(name))
                .collect(Collectors.toSet());
        if (names.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> productIdByName = new LinkedHashMap<>();
        Map<String, Set<String>> occupiedSpecsByName = new HashMap<>();
        List<String> chunk = new ArrayList<>(QUERY_CHUNK);
        for (String name : names) {
            chunk.add(name);
            if (chunk.size() >= QUERY_CHUNK) {
                collectExistingProducts(chunk, productIdByName, occupiedSpecsByName);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            collectExistingProducts(chunk, productIdByName, occupiedSpecsByName);
        }

        Map<String, ExistingProduct> result = new LinkedHashMap<>();
        productIdByName.forEach((name, productId) -> result.put(name,
                new ExistingProduct(productId, occupiedSpecsByName.getOrDefault(name, Set.of()))));
        return result;
    }

    private void collectExistingProducts(List<String> names,
                                         Map<String, Long> productIdByName,
                                         Map<String, Set<String>> occupiedSpecsByName) {
        for (OccupiedProductSpec row : productMapper.selectOccupiedProductSpecs(names)) {
            productIdByName.putIfAbsent(row.getName(), row.getProductId());
            // 查重 SQL 是 LEFT JOIN：商品存在但 SKU 全被逻辑删除时会返回 specs = null 的行，
            // 那种商品没有占用任何规格，不能记进占用集合
            if (row.getSpecs() != null) {
                occupiedSpecsByName.computeIfAbsent(row.getName(), key -> new HashSet<>())
                        .add(SkuSpecNormalizer.normalize(row.getSpecs()));
            }
        }
    }

    private List<ExcelRow> readRows(ExcelTaskContext context) {
        ExcelReadOptions options = ExcelReadOptions.defaults()
                .fileType(ExcelFileType.fromFileName(context.getFileName()))
                .maxRows(excelTaskProperties.getMaxImportRows())
                // 声明模板表头：业务侧是按列下标取值的，用户删列/挪列会让取值整体错位，
                // 而错位后的值往往仍能通过类型与范围校验，最终「把数据导成另一个字段」且任务报成功
                .expectedHeaders(HEADERS);
        try (InputStream in = context.openSource()) {
            return ExcelSupport.readAll(in, options);
        } catch (ExcelReadException e) {
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), e.getMessage());
        } catch (IOException e) {
            log.warn("读取商品导入文件失败 taskNo={}", context.getTaskNo(), e);
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(),
                    "文件读取失败，请重新导出后重试");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), "请选择要导入的文件");
        }
        if (ExcelFileType.fromFileName(file.getOriginalFilename()) == null) {
            throw new BusinessException(ResultCode.PRODUCT_IMPORT_FILE_INVALID.getCode(), "仅支持 .xlsx / .xls 格式");
        }
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /**
     * 一个待落库的商品
     *
     * <p>两种形态二选一：{@link #create}（新建 SPU，落库时 {@code insertBatch}）或
     * {@link #reuse}（复用已有 SPU，只追加 SKU、不建 SPU）。用工厂方法而不是两个可空字段的
     * 裸构造器，避免出现「两个都给了」或「两个都没给」的非法状态。
     *
     * <p>保留来源行是为了在落库失败时把原因铺到组内每一行——
     * 只报「第 N 行」而运营不知道同组还有哪些行一起失败了。
     */
    private record ProductGroup(Product product, Long existingProductId, List<SkuDraft> skus, List<ExcelRow> rows) {

        static ProductGroup create(Product product, List<SkuDraft> skus, List<ExcelRow> rows) {
            return new ProductGroup(product, null, skus, rows);
        }

        static ProductGroup reuse(Long existingProductId, List<SkuDraft> skus, List<ExcelRow> rows) {
            return new ProductGroup(null, existingProductId, skus, rows);
        }
    }

    /**
     * 一个待落库的 SKU：实体 + 该行想要的目标库存
     *
     * <p>为什么要多带一个 {@code stock}：{@code product_sku} 表自 V10 起没有库存列，
     * 库存只存在于 {@code inventory} 表；而建库存行必须在 SKU 落库拿到 id <b>之后</b>
     * （自定义批量插入不回填主键，得按 sku_code 回查）。中间这段路没有别的地方能存这个值。
     */
    private record SkuDraft(ProductSku sku, int stock) {
    }

    /**
     * 库里已有的一个商品：{@code productId} + 它已占用的<b>规范化规格</b>集合
     */
    private record ExistingProduct(Long productId, Set<String> occupiedSpecs) {
    }

    /**
     * 进度与失败计数记录器
     *
     * <p>刻意做成局部对象而不是 Service 的字段：Service 是单例，
     * 两个导入任务并发时用字段计数会互相污染。
     */
    private static final class ImportRecorder {

        private final ExcelTaskContext context;

        /** 连分组都没进去的行（商品名为空/超长），它们一开始就是失败的 */
        private final int ungroupedRows;

        private int processedInGroups;

        private int failRows;

        private int successSkus;

        private ImportRecorder(ExcelTaskContext context, int ungroupedRows) {
            this.context = context;
            this.ungroupedRows = ungroupedRows;
            // 刻意不在这里预置 failRows：没进分组的行稍后都会通过 error(...) 逐条记录，
            // 预置一次就会把它们重复计一遍（1 行失败被报成 2 行）
        }

        private void error(int rowNum, String message) {
            context.addError(rowNum, message);
            failRows++;
        }

        private void groupError(List<ExcelRow> rows, String message) {
            for (ExcelRow row : rows) {
                error(row.getRowNum(), message);
            }
        }

        private void advance(int rowsInGroup) {
            processedInGroups += rowsInGroup;
        }

        private void successSkus(int count) {
            successSkus += count;
        }

        private void flush() {
            context.reportProgress(ungroupedRows + processedInGroups, successSkus, failRows);
        }

        private int failRows() {
            return failRows;
        }

        private int successSkus() {
            return successSkus;
        }
    }
}
