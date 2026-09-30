package com.springshop.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.springshop.cart.dto.CartAddRequest;
import com.springshop.cart.dto.CartCheckedRequest;
import com.springshop.cart.dto.CartQuantityRequest;
import com.springshop.cart.entity.CartItem;
import com.springshop.cart.mapper.CartItemMapper;
import com.springshop.cart.service.CartService;
import com.springshop.cart.vo.CartItemVO;
import com.springshop.cart.vo.CartVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 购物车服务实现
 *
 * <p>依赖商品模块的 SKU/商品 Mapper 做只读校验与详情聚合，不反向写入商品数据。
 */
@Service
public class CartServiceImpl implements CartService {

    private static final String REASON_SKU_LOST = "商品已失效";

    private static final String REASON_SKU_DISABLED = "该规格已停售";

    private static final String REASON_PRODUCT_OFF_SHELF = "商品已下架";

    private final CartItemMapper cartItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;

    public CartServiceImpl(CartItemMapper cartItemMapper,
                           ProductSkuMapper productSkuMapper,
                           ProductMapper productMapper) {
        this.cartItemMapper = cartItemMapper;
        this.productSkuMapper = productSkuMapper;
        this.productMapper = productMapper;
    }

    @Override
    public void add(Long userId, CartAddRequest request) {
        validateQuantity(request.getQuantity());

        ProductSku sku = requireOnSaleSku(request.getSkuId());

        CartItem existing = findItem(userId, request.getSkuId());
        if (existing == null) {
            if (sku.getStock() != null && request.getQuantity() > sku.getStock()) {
                throw new BusinessException(ResultCode.CART_STOCK_INSUFFICIENT);
            }
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setSkuId(request.getSkuId());
            item.setQuantity(request.getQuantity());
            item.setChecked(1);
            try {
                cartItemMapper.insert(item);
                return;
            } catch (DuplicateKeyException e) {
                // 并发窗口：查询与插入之间另一请求已写入同一 SKU，退化为累加，避免唯一键冲突冒泡为系统异常
                existing = findItem(userId, request.getSkuId());
                if (existing == null) {
                    throw e;
                }
            }
        }

        // 同 SKU 已存在则累加，并按加购语义自动勾选
        int targetQuantity = request.getQuantity() + existing.getQuantity();
        if (sku.getStock() != null && targetQuantity > sku.getStock()) {
            throw new BusinessException(ResultCode.CART_STOCK_INSUFFICIENT);
        }
        existing.setQuantity(targetQuantity);
        existing.setChecked(1);
        cartItemMapper.updateById(existing);
    }

    @Override
    public CartVO list(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getId));

        CartVO cartVO = new CartVO();
        List<CartItemVO> itemVOs = new ArrayList<>();
        cartVO.setItems(itemVOs);

        if (items.isEmpty()) {
            cartVO.setTotalQuantity(0);
            cartVO.setCheckedQuantity(0);
            cartVO.setCheckedAmount(BigDecimal.ZERO);
            return cartVO;
        }

        // 批量查 SKU 与商品，避免逐条查询产生 N+1
        Map<Long, ProductSku> skuMap = collectSkus(items);
        Map<Long, Product> productMap = collectProducts(skuMap.values());

        int totalQuantity = 0;
        int checkedQuantity = 0;
        BigDecimal checkedAmount = BigDecimal.ZERO;
        for (CartItem item : items) {
            CartItemVO itemVO = buildItemVO(item, skuMap, productMap);
            itemVOs.add(itemVO);

            int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
            totalQuantity += quantity;
            // 失效条目即使勾选也不计入结算汇总
            if (Boolean.FALSE.equals(itemVO.getInvalid()) && isChecked(item)) {
                checkedQuantity += quantity;
                if (itemVO.getSubtotal() != null) {
                    checkedAmount = checkedAmount.add(itemVO.getSubtotal());
                }
            }
        }
        cartVO.setTotalQuantity(totalQuantity);
        cartVO.setCheckedQuantity(checkedQuantity);
        cartVO.setCheckedAmount(checkedAmount);
        return cartVO;
    }

    @Override
    public void updateQuantity(Long userId, Long id, CartQuantityRequest request) {
        validateQuantity(request.getQuantity());
        CartItem item = requireOwnedItem(userId, id);

        // 与加购保持一致的校验强度：失效 SKU / 下架商品不允许改数量
        ProductSku sku = requireOnSaleSku(item.getSkuId());
        if (sku.getStock() != null && request.getQuantity() > sku.getStock()) {
            throw new BusinessException(ResultCode.CART_STOCK_INSUFFICIENT);
        }
        item.setQuantity(request.getQuantity());
        cartItemMapper.updateById(item);
    }

    @Override
    public void updateChecked(Long userId, Long id, CartCheckedRequest request) {
        CartItem item = requireOwnedItem(userId, id);
        item.setChecked(Boolean.TRUE.equals(request.getChecked()) ? 1 : 0);
        cartItemMapper.updateById(item);
    }

    @Override
    public void updateAllChecked(Long userId, CartCheckedRequest request) {
        boolean checked = Boolean.TRUE.equals(request.getChecked());
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        if (items.isEmpty()) {
            return;
        }

        List<Long> targetIds;
        if (checked) {
            // 全选只作用于有效条目，失效条目不参与勾选（仍保留在列表中由用户自行删除）
            Map<Long, ProductSku> skuMap = collectSkus(items);
            Map<Long, Product> productMap = collectProducts(skuMap.values());
            targetIds = items.stream()
                    .filter(item -> isItemValid(item, skuMap, productMap))
                    .map(CartItem::getId)
                    .toList();
        } else {
            targetIds = items.stream().map(CartItem::getId).toList();
        }
        if (targetIds.isEmpty()) {
            return;
        }
        cartItemMapper.update(null, new LambdaUpdateWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .in(CartItem::getId, targetIds)
                .set(CartItem::getChecked, checked ? 1 : 0));
    }

    @Override
    public void delete(Long userId, Long id) {
        requireOwnedItem(userId, id);
        cartItemMapper.deleteById(id);
    }

    @Override
    public void deleteChecked(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getChecked, 1));
    }

    @Override
    public void clear(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
    }

    /**
     * 校验数量合法性，防止绕过 DTO 校验的直接调用传入非法值
     */
    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ResultCode.CART_QUANTITY_INVALID);
        }
    }

    private CartItem findItem(Long userId, Long skuId) {
        return cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId));
    }

    /**
     * 取出属于当前用户的条目，不存在或归属他人一律视为不存在，避免越权操作他人购物车
     */
    private CartItem requireOwnedItem(Long userId, Long id) {
        CartItem item = cartItemMapper.selectById(id);
        if (item == null || !Objects.equals(item.getUserId(), userId)) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }
        return item;
    }

    /**
     * 校验 SKU 处于可购买状态：SKU 存在（2011）、规格在售（3004）、所属商品在售（2014），返回 SKU
     */
    private ProductSku requireOnSaleSku(Long skuId) {
        ProductSku sku = productSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_NOT_FOUND);
        }
        if (!Objects.equals(sku.getStatus(), 1)) {
            throw new BusinessException(ResultCode.CART_SKU_DISABLED);
        }
        Product product = productMapper.selectById(sku.getProductId());
        if (product == null || !Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return sku;
    }

    private boolean isChecked(CartItem item) {
        return item.getChecked() != null && item.getChecked() == 1;
    }

    /**
     * 判断条目是否有效：SKU 存在且在售、所属商品存在且在售
     */
    private boolean isItemValid(CartItem item, Map<Long, ProductSku> skuMap, Map<Long, Product> productMap) {
        ProductSku sku = skuMap.get(item.getSkuId());
        if (sku == null || !Objects.equals(sku.getStatus(), 1)) {
            return false;
        }
        Product product = productMap.get(sku.getProductId());
        return product != null && Objects.equals(product.getStatus(), 1);
    }

    private Map<Long, ProductSku> collectSkus(List<CartItem> items) {
        Set<Long> skuIds = items.stream()
                .map(CartItem::getSkuId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSku> skus = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                .in(ProductSku::getId, skuIds));
        return skus.stream().collect(Collectors.toMap(ProductSku::getId, sku -> sku));
    }

    private Map<Long, Product> collectProducts(Collection<ProductSku> skus) {
        Set<Long> productIds = skus.stream()
                .map(ProductSku::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .in(Product::getId, productIds));
        return products.stream().collect(Collectors.toMap(Product::getId, product -> product));
    }

    private CartItemVO buildItemVO(CartItem item, Map<Long, ProductSku> skuMap, Map<Long, Product> productMap) {
        CartItemVO vo = new CartItemVO();
        vo.setId(item.getId());
        vo.setSkuId(item.getSkuId());
        vo.setQuantity(item.getQuantity());
        vo.setChecked(isChecked(item));
        vo.setInvalid(false);

        ProductSku sku = skuMap.get(item.getSkuId());
        if (sku == null) {
            markInvalid(vo, REASON_SKU_LOST);
            return vo;
        }
        Product product = productMap.get(sku.getProductId());
        vo.setProductId(sku.getProductId());
        vo.setSpecs(sku.getSpecs());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        vo.setStock(sku.getStock());
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setProductImage(product.getMainImage());
        }

        if (!Objects.equals(sku.getStatus(), 1)) {
            markInvalid(vo, REASON_SKU_DISABLED);
            return vo;
        }
        if (product == null || !Objects.equals(product.getStatus(), 1)) {
            markInvalid(vo, REASON_PRODUCT_OFF_SHELF);
            return vo;
        }
        if (sku.getPrice() != null && item.getQuantity() != null) {
            vo.setSubtotal(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return vo;
    }

    private void markInvalid(CartItemVO vo, String reason) {
        vo.setInvalid(true);
        vo.setInvalidReason(reason);
    }
}
