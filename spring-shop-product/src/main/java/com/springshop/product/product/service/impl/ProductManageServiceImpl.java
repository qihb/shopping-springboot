package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductImageItem;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.dto.ProductSkuItem;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductManageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 商品写服务实现（事务内落 SPU + SKU + Image）
 */
@Service
public class ProductManageServiceImpl implements ProductManageService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductImageMapper productImageMapper;
    private final ProductCategoryMapper categoryMapper;

    public ProductManageServiceImpl(ProductMapper productMapper,
                                    ProductSkuMapper productSkuMapper,
                                    ProductImageMapper productImageMapper,
                                    ProductCategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.productImageMapper = productImageMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveRequest request) {
        validateSkusNotEmpty(request);
        validateCategoryExists(request.getCategoryId());
        validateSkuCodesUnique(request.getSkus(), null);

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
        validateCategoryExists(request.getCategoryId());
        validateSkuCodesUnique(request.getSkus(), id);

        buildProduct(request, product);
        productMapper.updateById(product);

        saveSkus(id, request.getSkus(), false);
        saveImages(id, request.getImages(), false);
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

    private void saveSkus(Long productId, List<ProductSkuItem> items, boolean isCreate) {
        if (!isCreate) {
            productSkuMapper.delete(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, productId));
        }
        if (items == null) {
            return;
        }
        for (ProductSkuItem item : items) {
            ProductSku sku = new ProductSku();
            sku.setProductId(productId);
            sku.setSkuCode(item.getSkuCode());
            sku.setSpecs(item.getSpecs());
            sku.setPrice(item.getPrice());
            sku.setOriginalPrice(item.getOriginalPrice());
            sku.setStock(Objects.requireNonNullElse(item.getStock(), 0));
            sku.setStatus(Objects.requireNonNullElse(item.getStatus(), 1));
            productSkuMapper.insert(sku);
        }
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
