package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.common.security.UserContext;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.OccupiedProductSpec;
import com.springshop.product.product.dto.ProductImageItem;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.dto.ProductSkuItem;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import com.springshop.product.product.service.ProductManageService;
import com.springshop.product.product.support.SkuSpecNormalizer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品写服务实现（事务内落 SPU + SKU + Image）
 *
 * <p><b>库存（V9 起）</b>：库存以独立表 {@code inventory} 为准，SKU 落库后必须同步建库存行，
 * 否则该 SKU 在下单时会被判为「库存不足」（库存行缺失）。迁移期镜像列
 * {@code product_sku.stock} 已由 V10 删除，实体上不再有库存字段，所有库存读写都经
 * {@link InventoryService}。
 *
 * <p><b>唯一性（2026-10-09 起）</b>：唯一性单元是 {@code (商品名称, 规格)}（SKU 粒度），
 * 判定范围仅未删除记录；同名不同规格是合法的（同一 SPU 下的两个 SKU），
 * 同名同规格必须拒绝。规格用 {@link SkuSpecNormalizer} 规范化后比较。
 *
 * <p><b>修改商品走「按规格就地更新」而不是「删旧插新」</b>：
 * 旧实现先逻辑删除全部 SKU 再重新插入，由此带来三个必然故障 ——
 * 旧行仍占着 {@code uk_sku_code} 导致插入撞唯一键（改商品必 500）、
 * 新 SKU 拿到新 id 让 {@code cart_item.sku_id} 变成脏引用、
 * 以及库存被静默清零。现在改为「匹配到就地更新（保留 sku_id）、匹配不到才新增、
 * 请求里没有的逻辑删除」。
 */
@Service
public class ProductManageServiceImpl implements ProductManageService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductImageMapper productImageMapper;
    private final ProductCategoryMapper categoryMapper;
    private final InventoryService inventoryService;
    private final StringRedisTemplate stringRedisTemplate;

    public ProductManageServiceImpl(ProductMapper productMapper,
                                    ProductSkuMapper productSkuMapper,
                                    ProductImageMapper productImageMapper,
                                    ProductCategoryMapper categoryMapper,
                                    InventoryService inventoryService,
                                    StringRedisTemplate stringRedisTemplate) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.productImageMapper = productImageMapper;
        this.categoryMapper = categoryMapper;
        this.inventoryService = inventoryService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveRequest request) {
        validateSkusNotEmpty(request);
        validateSkuCodesUnique(request.getSkus(), null);
        // 唯一性校验排在分类校验之前：名称+规格已存在时这次新增注定失败，
        // 先报「分类不存在」会把运营引到无关的错处去改（与导入侧同一取舍）
        validateIdentitiesUnique(request.getSkus(), null, request.getName());
        validateCategoryExists(request.getCategoryId());

        Product product = buildProduct(request, new Product());
        productMapper.insert(product);

        saveSkus(product.getId(), request.getSkus(), true);
        saveImages(product.getId(), request.getImages(), true);
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductSaveRequest request) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        validateSkusNotEmpty(request);
        validateSkuCodesUnique(request.getSkus(), id);
        // 排除本商品自己：按商品粒度整批排除（不是按 SKU id），
        // 否则旧实现重建 SKU 后 id 全变，排除会失效
        validateIdentitiesUnique(request.getSkus(), id, request.getName());
        validateCategoryExists(request.getCategoryId());

        buildProduct(request, product);
        productMapper.updateById(product);

        saveSkus(id, request.getSkus(), false);
        saveImages(id, request.getImages(), false);

        // 商品变更后主动失效详情缓存
        stringRedisTemplate.delete(RedisKeys.productDetail(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        // 只有下架商品可删：上架商品可能正被下单/加购，直接删除会让前台出现
        // 「列表里没有、详情却还能打开」的中间态
        if (!Objects.equals(product.getStatus(), 0)) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_OFF_SHELF);
        }

        // 连带逻辑删除 SKU 与图片：残留 SKU 的 sku_code 仍占着全局唯一编码，
        // 会让「同一个编码以后永远不能再用」，语义不干净
        productSkuMapper.delete(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id));
        productImageMapper.delete(new LambdaQueryWrapper<ProductImage>().eq(ProductImage::getProductId, id));
        productMapper.deleteById(id);

        // 与 updateStatus 一致：删完必须失效详情缓存，否则前台在 TTL 内仍能打开已删商品
        stringRedisTemplate.delete(RedisKeys.productDetail(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        product.setStatus(status);
        productMapper.updateById(product);

        // 上下架变更后主动失效详情缓存
        stringRedisTemplate.delete(RedisKeys.productDetail(id));
    }

    private void validateSkusNotEmpty(ProductSaveRequest request) {
        if (request.getSkus() == null || request.getSkus().isEmpty()) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_EMPTY);
        }
    }

    private void validateCategoryExists(Long categoryId) {
        ProductCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
    }

    private void validateSkuCodesUnique(List<ProductSkuItem> skus, Long productId) {
        Set<String> seen = new HashSet<>();
        for (ProductSkuItem item : skus) {
            if (!seen.add(item.getSkuCode())) {
                throw new BusinessException(ResultCode.PRODUCT_SKU_CODE_DUPLICATE);
            }
            Long count = productSkuMapper.selectCount(new LambdaQueryWrapper<ProductSku>()
                    .eq(ProductSku::getSkuCode, item.getSkuCode())
                    .ne(productId != null, ProductSku::getProductId, productId));
            if (count != null && count > 0) {
                throw new BusinessException(ResultCode.PRODUCT_SKU_CODE_DUPLICATE);
            }
        }
    }

    /**
     * 校验 {@code (商品名称, 规格)} 在「未删除」范围内唯一
     *
     * <p>唯一性单元是 SKU 粒度（名称 + 规格），<b>不是</b> {@code sku_code} ——
     * 两套口径并存，互不替代。同名 + 不同规格是合法的（同一 SPU 下两个 SKU）。
     * 规格一律用 {@link SkuSpecNormalizer} 规范化后比较，否则段序/全角符号/空格
     * 都能把「重复」绕过去。
     *
     * <p>拦两类重复：
     * <ol>
     *   <li><b>与库中已有记录重复</b>：一次 join 查回该名称下所有未删除 SKU 的规格；
     *       修改商品时按<b>商品粒度</b>排除本商品（不是按 SKU id —— 旧实现会重建 id）；</li>
     *   <li><b>本次提交内部重复</b>：同一请求里两行规格规范化后相同。
     *       {@link #validateSkuCodesUnique} 只查 {@code sku_code}，拦不住这种。</li>
     * </ol>
     *
     * <p>⚠️ 这是<b>应用层兜底</b>，不是数据库约束：查重与落库之间仍有极窄窗口。
     * 唯一性键横跨 {@code product} 与 {@code product_sku} 两张表，单条唯一索引表达不了，
     * 库级强化（生成列 + 唯一索引）留作后续，需先验证 H2 是否支持该 DDL。
     */
    private void validateIdentitiesUnique(List<ProductSkuItem> skus, Long selfProductId, String productName) {
        if (skus == null || skus.isEmpty()) {
            return;
        }

        Set<String> seenSpecs = new HashSet<>(skus.size());
        for (ProductSkuItem item : skus) {
            if (!seenSpecs.add(SkuSpecNormalizer.normalize(item.getSpecs()))) {
                throw new BusinessException(ResultCode.PRODUCT_IDENTITY_DUPLICATE.getCode(),
                        "提交的规格「" + displaySpecs(item.getSpecs()) + "」重复，同一商品下规格不能重复");
            }
        }

        List<OccupiedProductSpec> occupied = productMapper.selectOccupiedProductSpecs(List.of(productName));
        for (OccupiedProductSpec row : occupied) {
            if (selfProductId != null && selfProductId.equals(row.getProductId())) {
                continue;
            }
            // 查重 SQL 是 LEFT JOIN：商品存在但 SKU 全被逻辑删除时会返回 specs = null 的行，
            // 那种商品并没有占用任何规格，必须跳过（否则会把「无规格」误判成重复）
            if (row.getSpecs() == null) {
                continue;
            }
            String occupiedKey = SkuSpecNormalizer.normalize(row.getSpecs());
            for (ProductSkuItem item : skus) {
                if (occupiedKey.equals(SkuSpecNormalizer.normalize(item.getSpecs()))) {
                    throw new BusinessException(ResultCode.PRODUCT_IDENTITY_DUPLICATE.getCode(),
                            "商品「" + productName + "」已存在规格「" + displaySpecs(row.getSpecs())
                                    + "」，同名同规格不可重复；如要追加规格请换一个规格值");
                }
            }
        }
    }

    /**
     * 规格为空时给一个可读的占位，避免错误提示里出现 {@code null}
     */
    private String displaySpecs(String specs) {
        return (specs == null || specs.isBlank()) ? "（无规格）" : specs;
    }

    private Product buildProduct(ProductSaveRequest request, Product product) {
        product.setCategoryId(request.getCategoryId());
        product.setName(request.getName());
        product.setSubtitle(request.getSubtitle());
        product.setMainImage(request.getMainImage());
        product.setDetail(request.getDetail());
        if (product.getSales() == null) {
            product.setSales(0);
        }
        if (request.getStatus() != null) {
            product.setStatus(request.getStatus());
        } else if (product.getStatus() == null) {
            product.setStatus(1);
        }
        return product;
    }

    /**
     * 落 SKU：<b>按规范化规格 upsert</b>，匹配到就就地更新（保留 {@code sku_id}），
     * 匹配不到才新增，请求里没有的逻辑删除
     *
     * <p>前端契约是「提交即全量」（请求体里的 SKU 列表代表该商品当前的全部 SKU），
     * 所以第 3 步要删掉请求里没出现的旧 SKU。
     *
     * <p><b>为什么不沿用「整批删旧插新」</b>：那样做有三个必然故障 ——
     * <ol>
     *   <li>逻辑删除后旧行仍在表里、{@code sku_code} 仍占着 {@code uk_sku_code}
     *       （该唯一索引不区分 {@code is_deleted}），紧接着插入同一个编码必撞唯一键，
     *       <b>于是「修改商品」永远 500</b>；</li>
     *   <li>新 SKU 拿到新自增 id，而 {@code cart_item.sku_id} / {@code order_item.sku_id}
     *       仍指向旧 SKU，购物车里该商品的型号信息查不到；</li>
     *   <li>库存被当成「新 SKU 的初始值」，前端没传 stock 时静默清零。</li>
     * </ol>
     *
     * <p><b>删除被移除的 SKU 前不检查购物车引用</b>（2026-10-09 决策）：
     * {@code cart_item} 引用一个已删 SKU 时，购物车会把它标记为「商品已失效」——
     * 这个状态前台本来就要处理，属于既有行为；加一道拦截反而会让运营「只想改价」
     * 时被卡住（且没有强制通道）。
     *
     * @param isCreate true = 新建商品（库里必然没有旧 SKU，跳过查询）
     */
    private void saveSkus(Long productId, List<ProductSkuItem> items, boolean isCreate) {
        if (items == null || items.isEmpty()) {
            // 契约上不会走到这里（validateSkusNotEmpty 已拦），仅作防御
            return;
        }

        // 现有未删除 SKU，按「规范化规格」建索引；历史脏数据里同规格重复时保留先查到的一条
        Map<String, ProductSku> existingBySpec = isCreate
                ? Map.of()
                : productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getProductId, productId))
                .stream()
                .collect(Collectors.toMap(
                        sku -> SkuSpecNormalizer.normalize(sku.getSpecs()),
                        sku -> sku,
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        Set<Long> retainedIds = new HashSet<>(items.size());
        for (ProductSkuItem item : items) {
            ProductSku existing = existingBySpec.get(SkuSpecNormalizer.normalize(item.getSpecs()));
            if (existing == null) {
                insertSku(productId, item);
            } else {
                retainedIds.add(existing.getId());
                updateSku(existing, item);
            }
        }

        List<Long> removedIds = existingBySpec.values().stream()
                .map(ProductSku::getId)
                .filter(id -> !retainedIds.contains(id))
                .toList();
        if (!removedIds.isEmpty()) {
            // 走 lambda wrapper 而不是 deleteBatchIds：逻辑删除由 MP 插件处理
            productSkuMapper.delete(new LambdaQueryWrapper<ProductSku>().in(ProductSku::getId, removedIds));
        }
    }

    /**
     * 新增 SKU 并同步建库存行
     *
     * <p>新增时库存为 {@code null} 视为 0（没有任何「原值」可保留）。
     */
    private void insertSku(Long productId, ProductSkuItem item) {
        int stock = Objects.requireNonNullElse(item.getStock(), 0);
        ProductSku sku = new ProductSku();
        sku.setProductId(productId);
        sku.setSkuCode(item.getSkuCode());
        sku.setSpecs(item.getSpecs());
        sku.setPrice(item.getPrice());
        sku.setOriginalPrice(item.getOriginalPrice());
        sku.setStatus(Objects.requireNonNullElse(item.getStatus(), 1));
        productSkuMapper.insert(sku);
        // 库存以 inventory 表为准：新 SKU 必须建库存行，否则下单时会被判为「库存不足」
        inventoryService.initStock(sku.getId(), stock);
    }

    /**
     * 就地更新已存在的 SKU（保留 id）
     *
     * <p>SKU 表本身已经没有任何库存字段（V10 删掉了镜像列），所以「库存怎么改」完全由
     * {@link #syncInventoryOnUpdate} 决定 —— 尤其是
     * <b>修改时库存为 {@code null} 必须保持原值</b>，不能当成 0：
     * {@code ProductSkuItem.stock} 没有 {@code @NotNull}，前端不传库存是常态，
     * 置 0 会把商品悄悄改成不可售。
     */
    private void updateSku(ProductSku sku, ProductSkuItem item) {
        sku.setSkuCode(item.getSkuCode());
        sku.setSpecs(item.getSpecs());
        sku.setPrice(item.getPrice());
        sku.setOriginalPrice(item.getOriginalPrice());
        sku.setStatus(Objects.requireNonNullElse(item.getStatus(), 1));
        productSkuMapper.updateById(sku);

        syncInventoryOnUpdate(sku.getId(), item);
    }

    /**
     * 修改商品时同步库存行
     *
     * <p>必须经 {@code InventoryService.adjust} 而不是直接改表 —— 后者会让
     * 「改了库存必有账」这条不变量失效（没有流水就无法对账）。
     * 值没变就不动，避免每次编辑都留一条无意义的调整流水。
     */
    private void syncInventoryOnUpdate(Long skuId, ProductSkuItem item) {
        if (item.getStock() == null) {
            return;
        }
        if (Objects.equals(inventoryService.getBySkuId(skuId).getStock(), item.getStock())) {
            return;
        }
        inventoryService.adjust(skuId, item.getStock(), UserContext.getUserId(), "商品编辑同步库存");
    }

    private void saveImages(Long productId, List<ProductImageItem> items, boolean isCreate) {
        if (!isCreate) {
            productImageMapper.delete(new LambdaQueryWrapper<ProductImage>().eq(ProductImage::getProductId, productId));
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        int idx = 0;
        for (ProductImageItem item : items) {
            ProductImage image = new ProductImage();
            image.setProductId(productId);
            image.setImageUrl(item.getImageUrl());
            image.setSort(item.getSort() != null ? item.getSort() : idx);
            productImageMapper.insert(image);
            idx++;
        }
    }
}
