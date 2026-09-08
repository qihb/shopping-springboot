package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductImageVO;
import com.springshop.product.product.vo.ProductListVO;
import com.springshop.product.product.vo.ProductSkuVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品读服务实现（聚合 SKU 最低价、分类名、图片排序）
 */
@Service
public class ProductQueryServiceImpl implements ProductQueryService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductImageMapper productImageMapper;
    private final ProductCategoryMapper categoryMapper;

    public ProductQueryServiceImpl(ProductMapper productMapper,
                                   ProductSkuMapper productSkuMapper,
                                   ProductImageMapper productImageMapper,
                                   ProductCategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.productImageMapper = productImageMapper;
        this.categoryMapper = categoryMapper;
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
    public ProductDetailVO adminDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return buildDetail(product);
    }

    @Override
    public ProductDetailVO appDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (!Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return buildDetail(product);
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
        List<ProductSkuVO> skuVos = new ArrayList<>(skus.stream().map(this::toSkuVO).toList());
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

    private ProductSkuVO toSkuVO(ProductSku sku) {
        ProductSkuVO vo = new ProductSkuVO();
        vo.setId(sku.getId());
        vo.setSkuCode(sku.getSkuCode());
        vo.setSpecs(sku.getSpecs());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        vo.setStock(sku.getStock());
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
