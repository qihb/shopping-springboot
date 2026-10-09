package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductExportQuery;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductImageVO;
import com.springshop.product.product.vo.ProductListVO;
import com.springshop.product.product.vo.ProductSkuVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 商品读服务实现（聚合 SKU 最低价、分类名、图片排序）
 */
@Service
public class ProductQueryServiceImpl implements ProductQueryService {

    /** 商品详情缓存基础时长（分钟） */
    private static final long DETAIL_CACHE_MINUTES = 30;

    /** 缓存 TTL 随机抖动上限（分钟），避免大量 key 同时过期引发雪崩 */
    private static final long DETAIL_CACHE_JITTER_MINUTES = 5;

    /** 不存在商品的空值缓存时长（秒），防止恶意 id 反复穿透查库 */
    private static final long NULL_VALUE_CACHE_SECONDS = 60;

    /** 空值缓存占位符 */
    private static final String NULL_VALUE_MARKER = "NULL";

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductImageMapper productImageMapper;
    private final ProductCategoryMapper categoryMapper;
    private final InventoryService inventoryService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public ProductQueryServiceImpl(ProductMapper productMapper,
                                   ProductSkuMapper productSkuMapper,
                                   ProductImageMapper productImageMapper,
                                   ProductCategoryMapper categoryMapper,
                                   InventoryService inventoryService,
                                   StringRedisTemplate stringRedisTemplate,
                                   ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.productImageMapper = productImageMapper;
        this.categoryMapper = categoryMapper;
        this.inventoryService = inventoryService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<ProductListVO> adminPage(ProductPageQuery query) {
        IPage<Product> page = productMapper.selectPage(query.toPage(), buildProductQueryWrapper(query, false));
        return buildListPageResult(page);
    }

    @Override
    public PageResult<ProductListVO> appPage(ProductPageQuery query) {
        if (query == null) {
            query = new ProductPageQuery();
        }
        query.setStatus(1);
        IPage<Product> page = productMapper.selectPage(query.toPage(), buildProductQueryWrapper(query, true));
        return buildListPageResult(page);
    }

    @Override
    public List<ProductListVO> adminExportPage(ProductExportQuery query, Long lastId, long pageSize) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getIds() != null && !query.getIds().isEmpty()) {
                // 「导出选中」优先于其他条件：用户勾了行就是明确的意图
                wrapper.in(Product::getId, query.getIds());
            } else {
                if (query.getCategoryId() != null) {
                    wrapper.eq(Product::getCategoryId, query.getCategoryId());
                }
                if (StringUtils.hasText(query.getKeyword())) {
                    wrapper.like(Product::getName, query.getKeyword());
                }
                if (query.getStatus() != null) {
                    wrapper.eq(Product::getStatus, query.getStatus());
                }
            }
        }
        // keyset 游标：只取「比上一页最后一条更小」的 id，窗口不会因为别人新增商品而漂移
        wrapper.lt(lastId != null, Product::getId, lastId);
        wrapper.orderByDesc(Product::getId);
        // 游标分页每页都取第一页；searchCount=false：导出不展示总页数，省掉每页一次 COUNT
        IPage<Product> page = productMapper.selectPage(new Page<>(1, pageSize, false), wrapper);
        return buildListPageResult(page).getRecords();
    }

    @Override
    public ProductDetailVO adminDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return buildDetail(product);
    }

    @Override
    public ProductDetailVO appDetail(Long id) {
        String cacheKey = RedisKeys.productDetail(id);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (NULL_VALUE_MARKER.equals(cached)) {
            // 空值缓存命中：商品不存在，直接抛业务异常
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, ProductDetailVO.class);
            } catch (JsonProcessingException e) {
                // 缓存内容损坏视为未命中，走库重建
            }
        }

        Product product = productMapper.selectById(id);
        if (product == null) {
            // 空值短缓存 60s，防止不存在的 id 反复穿透查库
            stringRedisTemplate.opsForValue().set(cacheKey, NULL_VALUE_MARKER,
                    Duration.ofSeconds(NULL_VALUE_CACHE_SECONDS));
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (!Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        ProductDetailVO vo = buildDetail(product);

        // TTL 基础 30 分钟 + 随机抖动，避免大量 key 同一时刻集中过期
        long jitterMinutes = ThreadLocalRandom.current().nextLong(DETAIL_CACHE_JITTER_MINUTES + 1);
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(vo),
                    Duration.ofMinutes(DETAIL_CACHE_MINUTES + jitterMinutes));
        } catch (JsonProcessingException e) {
            // 序列化失败只影响缓存写入，不影响本次响应
        }
        return vo;
    }

    private LambdaQueryWrapper<Product> buildProductQueryWrapper(ProductPageQuery query, boolean fixedStatus) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (query != null && query.getCategoryId() != null) {
            wrapper.eq(Product::getCategoryId, query.getCategoryId());
        }
        if (query != null && StringUtils.hasText(query.getKeyword())) {
            wrapper.like(Product::getName, query.getKeyword());
        }
        if (fixedStatus) {
            wrapper.eq(Product::getStatus, 1);
        } else if (query != null && query.getStatus() != null) {
            wrapper.eq(Product::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(Product::getId);
        return wrapper;
    }

    private PageResult<ProductListVO> buildListPageResult(IPage<Product> page) {
        List<Product> products = page.getRecords();
        List<ProductListVO> records;
        if (products == null || products.isEmpty()) {
            records = Collections.emptyList();
        } else {
            List<Long> productIds = products.stream().map(Product::getId).toList();
            Map<Long, BigDecimal> minPriceMap = collectMinPrice(productIds);
            Map<Long, String> categoryNameMap = collectCategoryNames(products.stream()
                    .map(Product::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet()));

            records = products.stream().map(p -> {
                ProductListVO vo = new ProductListVO();
                vo.setId(p.getId());
                vo.setCategoryId(p.getCategoryId());
                vo.setCategoryName(categoryNameMap.getOrDefault(p.getCategoryId(), null));
                vo.setName(p.getName());
                vo.setSubtitle(p.getSubtitle());
                vo.setMainImage(p.getMainImage());
                vo.setMinPrice(minPriceMap.get(p.getId()));
                vo.setSales(p.getSales());
                vo.setStatus(p.getStatus());
                vo.setCreateTime(p.getCreateTime());
                return vo;
            }).toList();
        }

        IPage<ProductListVO> resultPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        resultPage.setRecords(records);
        return PageResult.of(resultPage);
    }

    private Map<Long, BigDecimal> collectMinPrice(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSku> skus = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                .in(ProductSku::getProductId, productIds));
        Map<Long, BigDecimal> map = new HashMap<>();
        for (ProductSku sku : skus) {
            if (sku.getPrice() == null) {
                continue;
            }
            BigDecimal min = map.get(sku.getProductId());
            if (min == null || sku.getPrice().compareTo(min) < 0) {
                map.put(sku.getProductId(), sku.getPrice());
            }
        }
        return map;
    }

    private Map<Long, String> collectCategoryNames(Set<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .in(ProductCategory::getId, categoryIds));
        Map<Long, String> map = new HashMap<>();
        for (ProductCategory c : categories) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    private ProductDetailVO buildDetail(Product product) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        if (product.getCategoryId() != null) {
            ProductCategory category = categoryMapper.selectById(product.getCategoryId());
            if (category != null) {
                vo.setCategoryName(category.getName());
            }
        }
        vo.setName(product.getName());
        vo.setSubtitle(product.getSubtitle());
        vo.setMainImage(product.getMainImage());
        vo.setDetail(product.getDetail());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());

        List<ProductSku> skus = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getProductId, product.getId()));
        // 可售量取自库存表（在库 − 未付款订单锁定）。product_sku.stock 这个迁移期镜像列
        // 已由 V10 删除，库存的唯一来源就是 inventory
        Map<Long, Integer> availableMap = inventoryService.availableMap(
                skus.stream().map(ProductSku::getId).toList());
        List<ProductSkuVO> skuVos = new ArrayList<>(
                skus.stream().map(sku -> toSkuVO(sku, availableMap)).toList());
        skuVos.sort(Comparator.comparing(ProductSkuVO::getPrice, Comparator.nullsLast(BigDecimal::compareTo)));
        vo.setSkus(skuVos);
        if (!skuVos.isEmpty()) {
            vo.setMinPrice(skuVos.get(0).getPrice());
        }

        List<ProductImage> images = productImageMapper.selectList(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, product.getId())
                .orderByAsc(ProductImage::getSort));
        vo.setImages(images.stream().map(this::toImageVO).toList());
        return vo;
    }

    private ProductSkuVO toSkuVO(ProductSku sku, Map<Long, Integer> availableMap) {
        ProductSkuVO vo = new ProductSkuVO();
        vo.setId(sku.getId());
        vo.setSkuCode(sku.getSkuCode());
        vo.setSpecs(sku.getSpecs());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        // 对外暴露可售量：未付款订单锁定的部分不该显示为「有货」
        vo.setStock(availableMap.getOrDefault(sku.getId(), 0));
        vo.setStatus(sku.getStatus());
        return vo;
    }

    private ProductImageVO toImageVO(ProductImage image) {
        ProductImageVO vo = new ProductImageVO();
        vo.setId(image.getId());
        vo.setImageUrl(image.getImageUrl());
        vo.setSort(image.getSort());
        return vo;
    }
}
