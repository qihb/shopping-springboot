package com.springshop.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试（纯 Mockito，不启动 Spring 容器）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceImplTest {

    private static final Long USER_ID = 1L;

    @Mock
    private CartItemMapper cartItemMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private CartServiceImpl cartService;

    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { CartItem.class, Product.class, ProductSku.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @BeforeEach
    void setUpRedisMocks() {
        // 购物车缓存依赖 opsForHash，统一 stub 供读/写路径使用
        when(stringRedisTemplate.<Object, Object>opsForHash()).thenReturn(hashOperations);
    }

    // ---------------- 写操作 ----------------

    @Test
    void add_should_insert_when_sku_not_in_cart() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 5));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        cartService.add(USER_ID, addRequest(10L, 2));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).insert(captor.capture());
        CartItem saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(10L, saved.getSkuId());
        assertEquals(2, saved.getQuantity());
        assertEquals(1, saved.getChecked());
    }

    @Test
    void add_should_accumulate_and_check_when_sku_exists() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 10));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cartItem(9L, 10L, 3, 0));

        cartService.add(USER_ID, addRequest(10L, 2));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).updateById(captor.capture());
        verify(cartItemMapper, never()).insert(any(CartItem.class));
        assertEquals(5, captor.getValue().getQuantity());
        assertEquals(1, captor.getValue().getChecked());
    }

    @Test
    void add_should_fail_when_sku_not_found() {
        when(productSkuMapper.selectById(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(USER_ID, addRequest(10L, 1)));
        assertEquals(ResultCode.PRODUCT_SKU_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void add_should_fail_when_product_off_shelf() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 5));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(USER_ID, addRequest(10L, 1)));
        assertEquals(ResultCode.PRODUCT_OFF_SHELF.getCode(), ex.getCode());
    }

    @Test
    void add_should_fail_when_sku_disabled() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 0, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(USER_ID, addRequest(10L, 1)));
        assertEquals(ResultCode.CART_SKU_DISABLED.getCode(), ex.getCode());
    }

    @Test
    void add_should_fail_when_stock_insufficient() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 3));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(USER_ID, addRequest(10L, 5)));
        assertEquals(ResultCode.CART_STOCK_INSUFFICIENT.getCode(), ex.getCode());
    }

    @Test
    void add_should_fail_when_accumulated_quantity_exceeds_stock() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 4));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cartItem(9L, 10L, 3, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(USER_ID, addRequest(10L, 2)));
        assertEquals(ResultCode.CART_STOCK_INSUFFICIENT.getCode(), ex.getCode());
    }

    @Test
    void add_should_accumulate_when_concurrent_insert_conflicts() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 10));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        // 首次查询为空 → 走 insert；insert 抛唯一键冲突后重查能拿到并发写入的行
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null)
                .thenReturn(cartItem(9L, 10L, 3, 0));
        when(cartItemMapper.insert(any(CartItem.class)))
                .thenThrow(new DuplicateKeyException("uk_user_sku"));

        cartService.add(USER_ID, addRequest(10L, 2));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).updateById(captor.capture());
        assertEquals(5, captor.getValue().getQuantity());
        assertEquals(1, captor.getValue().getChecked());
    }

    @Test
    void updateQuantity_should_fail_when_item_not_found() {
        when(cartItemMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(USER_ID, 9L, quantityRequest(2)));
        assertEquals(ResultCode.CART_ITEM_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateQuantity_should_fail_when_quantity_illegal() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(USER_ID, 9L, quantityRequest(0)));
        assertEquals(ResultCode.CART_QUANTITY_INVALID.getCode(), ex.getCode());
    }

    @Test
    void updateQuantity_should_fail_when_stock_insufficient() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 2));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(USER_ID, 9L, quantityRequest(5)));
        assertEquals(ResultCode.CART_STOCK_INSUFFICIENT.getCode(), ex.getCode());
    }

    @Test
    void updateQuantity_should_fail_when_sku_disabled() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 0, 10));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(USER_ID, 9L, quantityRequest(2)));
        assertEquals(ResultCode.CART_SKU_DISABLED.getCode(), ex.getCode());
    }

    @Test
    void updateQuantity_should_fail_when_sku_deleted() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));
        when(productSkuMapper.selectById(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(USER_ID, 9L, quantityRequest(2)));
        assertEquals(ResultCode.PRODUCT_SKU_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateQuantity_should_update_when_ok() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 10));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));

        cartService.updateQuantity(USER_ID, 9L, quantityRequest(4));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getQuantity());
    }

    @Test
    void updateChecked_should_fail_when_item_not_found() {
        when(cartItemMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateChecked(USER_ID, 9L, checkedRequest(false)));
        assertEquals(ResultCode.CART_ITEM_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateChecked_should_update_checked_flag() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));

        cartService.updateChecked(USER_ID, 9L, checkedRequest(false));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getChecked());
    }

    @Test
    void delete_should_fail_when_item_not_found() {
        when(cartItemMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.delete(USER_ID, 9L));
        assertEquals(ResultCode.CART_ITEM_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void delete_should_remove_when_owner_matches() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));

        cartService.delete(USER_ID, 9L);

        verify(cartItemMapper).deleteById(9L);
    }

    @Test
    void delete_should_fail_when_item_belongs_to_other_user() {
        CartItem other = cartItem(9L, 10L, 1, 1);
        other.setUserId(999L);
        when(cartItemMapper.selectById(9L)).thenReturn(other);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.delete(USER_ID, 9L));
        assertEquals(ResultCode.CART_ITEM_NOT_FOUND.getCode(), ex.getCode());
        verify(cartItemMapper, never()).deleteById(9L);
    }

    @Test
    void updateAllChecked_should_uncheck_all_items() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(1L, 10L, 2, 1),
                cartItem(2L, 11L, 1, 1)
        ));

        cartService.updateAllChecked(USER_ID, checkedRequest(false));

        verify(cartItemMapper).update(isNull(), any());
    }

    @Test
    void updateAllChecked_should_check_valid_items() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(1L, 10L, 2, 0),
                cartItem(2L, 12L, 1, 0)
        ));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                skuWithPrice(10L, 100L, 1, 10, "10.00"),
                skuWithPrice(12L, 100L, 0, 10, "8.00")
        ));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product(100L, 1)));

        cartService.updateAllChecked(USER_ID, checkedRequest(true));

        verify(cartItemMapper).update(isNull(), any());
    }

    @Test
    void updateAllChecked_should_skip_update_when_all_items_invalid() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(1L, 10L, 2, 0)
        ));
        // SKU 已被删除 → 唯一条目也是失效条目，全选不应产生任何更新
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        cartService.updateAllChecked(USER_ID, checkedRequest(true));

        verify(cartItemMapper, never()).update(any(), any());
    }

    @Test
    void deleteChecked_should_delete_checked_items() {
        cartService.deleteChecked(USER_ID);

        verify(cartItemMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void clear_should_delete_all_items_of_user() {
        cartService.clear(USER_ID);

        verify(cartItemMapper).delete(any(LambdaQueryWrapper.class));
    }

    // ---------------- 读操作 ----------------

    @Test
    void list_should_return_empty_summary_when_cart_empty() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        CartVO vo = cartService.list(USER_ID);

        assertTrue(vo.getItems().isEmpty());
        assertEquals(0, vo.getTotalQuantity());
        assertEquals(0, vo.getCheckedQuantity());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getCheckedAmount()));
    }

    @Test
    void list_should_aggregate_and_mark_invalid() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(1L, 10L, 2, 1),
                cartItem(2L, 11L, 1, 0),
                cartItem(3L, 12L, 3, 1)
        ));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                skuWithPrice(10L, 100L, 1, 10, "10.00"),
                skuWithPrice(11L, 100L, 1, 10, "5.00"),
                skuWithPrice(12L, 100L, 0, 10, "8.00")
        ));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product(100L, 1)));

        CartVO vo = cartService.list(USER_ID);

        assertEquals(3, vo.getItems().size());
        assertEquals(6, vo.getTotalQuantity());
        assertEquals(2, vo.getCheckedQuantity());
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getCheckedAmount()));

        CartItemVO first = vo.getItems().get(0);
        assertEquals("测试商品", first.getProductName());
        assertEquals(0, new BigDecimal("20.00").compareTo(first.getSubtotal()));
        assertFalse(first.getInvalid());

        CartItemVO third = vo.getItems().get(2);
        assertTrue(third.getInvalid());
        assertEquals("该规格已停售", third.getInvalidReason());
    }

    @Test
    void list_should_mark_invalid_when_sku_deleted() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(1L, 10L, 2, 1)
        ));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        CartVO vo = cartService.list(USER_ID);

        CartItemVO item = vo.getItems().get(0);
        assertTrue(item.getInvalid());
        assertEquals("商品已失效", item.getInvalidReason());
        assertEquals(0, vo.getCheckedQuantity());
    }

    // ---------------- 缓存读路径（Redis 读加速，DB 为主存） ----------------

    @Test
    void list_should_hit_cache_without_querying_cart_table() {
        // 缓存命中：field=skuId，value=条目id|quantity|checked
        when(hashOperations.entries("cart:1")).thenReturn(hashEntries(
                "10", "9|2|1",
                "11", "8|1|0"
        ));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                skuWithPrice(10L, 100L, 1, 10, "10.00"),
                skuWithPrice(11L, 100L, 1, 10, "5.00")
        ));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product(100L, 1)));

        CartVO vo = cartService.list(USER_ID);

        // 购物车表零查询：条目状态数据全部来自 Redis
        verify(cartItemMapper, never()).selectList(any(LambdaQueryWrapper.class));
        assertEquals(2, vo.getItems().size());
        // 与 DB 列表一致：按条目 id 倒序
        CartItemVO first = vo.getItems().get(0);
        assertEquals(9L, first.getId());
        assertEquals(10L, first.getSkuId());
        assertEquals(2, first.getQuantity());
        assertTrue(first.getChecked());
        assertFalse(first.getInvalid());
        assertEquals(0, new BigDecimal("20.00").compareTo(first.getSubtotal()));

        CartItemVO second = vo.getItems().get(1);
        assertEquals(8L, second.getId());
        assertEquals(11L, second.getSkuId());
        assertEquals(1, second.getQuantity());
        assertFalse(second.getChecked());

        assertEquals(3, vo.getTotalQuantity());
        assertEquals(2, vo.getCheckedQuantity());
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getCheckedAmount()));
    }

    @Test
    void list_should_fallback_to_db_and_rebuild_when_cache_miss() {
        // HGETALL 返回空 map → miss，回源 DB
        when(hashOperations.entries("cart:1")).thenReturn(Map.of());
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(9L, 10L, 2, 1)
        ));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                skuWithPrice(10L, 100L, 1, 10, "10.00")
        ));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product(100L, 1)));

        CartVO vo = cartService.list(USER_ID);

        assertEquals(1, vo.getItems().size());
        assertEquals(2, vo.getTotalQuantity());
        // 整 cart 重建缓存 + 滑动续期 7 天
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> cacheCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("cart:1"), cacheCaptor.capture());
        assertEquals(Map.of("10", "9|2|1"), cacheCaptor.getValue());
        verify(stringRedisTemplate).expire(eq("cart:1"), eq(Duration.ofDays(7)));
    }

    @Test
    void list_should_fallback_to_db_when_cache_entry_corrupted() {
        // 任一 entry 解析失败 → 视为缓存损坏，整体回源并重建
        when(hashOperations.entries("cart:1")).thenReturn(hashEntries("10", "garbage"));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(9L, 10L, 2, 1)
        ));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                skuWithPrice(10L, 100L, 1, 10, "10.00")
        ));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product(100L, 1)));

        CartVO vo = cartService.list(USER_ID);

        assertEquals(1, vo.getItems().size());
        verify(cartItemMapper).selectList(any(LambdaQueryWrapper.class));
        verify(hashOperations).putAll(eq("cart:1"), anyMap());
    }

    // ---------------- 缓存写路径（DB 成功后同步 Redis） ----------------

    @Test
    void add_should_sync_redis_field_after_db_insert() {
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 5));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 模拟 MyBatis-Plus insert 后回填自增主键
        when(cartItemMapper.insert(any(CartItem.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CartItem.class).setId(9L);
            return 1;
        });

        cartService.add(USER_ID, addRequest(10L, 2));

        verify(hashOperations).putAll("cart:1", Map.of("10", "9|2|1"));
        verify(stringRedisTemplate).expire(eq("cart:1"), eq(Duration.ofDays(7)));
    }

    @Test
    void add_should_not_touch_redis_when_db_write_never_happens() {
        // 库存不足在 DB 写之前即抛异常，不应有任何缓存同步
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 3));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(USER_ID, addRequest(10L, 5)));
        assertEquals(ResultCode.CART_STOCK_INSUFFICIENT.getCode(), ex.getCode());
        verifyNoInteractions(hashOperations);
        verify(stringRedisTemplate, never()).expire(any(String.class), any(Duration.class));
    }

    @Test
    void updateQuantity_should_sync_redis_field_after_db_update() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));
        when(productSkuMapper.selectById(10L)).thenReturn(sku(10L, 100L, 1, 10));
        when(productMapper.selectById(100L)).thenReturn(product(100L, 1));

        cartService.updateQuantity(USER_ID, 9L, quantityRequest(4));

        verify(hashOperations).putAll("cart:1", Map.of("10", "9|4|1"));
    }

    @Test
    void updateChecked_should_sync_redis_field_after_db_update() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));

        cartService.updateChecked(USER_ID, 9L, checkedRequest(false));

        verify(hashOperations).putAll("cart:1", Map.of("10", "9|1|0"));
    }

    @Test
    void updateAllChecked_should_sync_redis_after_db_update() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                cartItem(1L, 10L, 2, 1),
                cartItem(2L, 11L, 1, 1)
        ));

        cartService.updateAllChecked(USER_ID, checkedRequest(false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> cacheCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("cart:1"), cacheCaptor.capture());
        assertEquals(Map.of("10", "1|2|0", "11", "2|1|0"), cacheCaptor.getValue());
        verify(stringRedisTemplate).expire(eq("cart:1"), eq(Duration.ofDays(7)));
    }

    @Test
    void delete_should_remove_redis_field_after_db_delete() {
        when(cartItemMapper.selectById(9L)).thenReturn(cartItem(9L, 10L, 1, 1));

        cartService.delete(USER_ID, 9L);

        verify(cartItemMapper).deleteById(9L);
        verify(hashOperations).delete("cart:1", "10");
    }

    @Test
    void deleteChecked_should_evict_whole_cart_cache_after_db_delete() {
        cartService.deleteChecked(USER_ID);

        verify(cartItemMapper).delete(any(LambdaQueryWrapper.class));
        verify(stringRedisTemplate).delete("cart:1");
    }

    @Test
    void clear_should_evict_whole_cart_cache_after_db_delete() {
        cartService.clear(USER_ID);

        verify(cartItemMapper).delete(any(LambdaQueryWrapper.class));
        verify(stringRedisTemplate).delete("cart:1");
    }

    // ---------------- 构造辅助 ----------------

    private CartAddRequest addRequest(Long skuId, Integer quantity) {
        CartAddRequest request = new CartAddRequest();
        request.setSkuId(skuId);
        request.setQuantity(quantity);
        return request;
    }

    private CartQuantityRequest quantityRequest(Integer quantity) {
        CartQuantityRequest request = new CartQuantityRequest();
        request.setQuantity(quantity);
        return request;
    }

    private CartCheckedRequest checkedRequest(boolean checked) {
        CartCheckedRequest request = new CartCheckedRequest();
        request.setChecked(checked);
        return request;
    }

    /**
     * 构造 Redis Hash 的 HGETALL 返回（field/value 交替传入）
     */
    private Map<Object, Object> hashEntries(Object... pairs) {
        Map<Object, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private CartItem cartItem(Long id, Long skuId, Integer quantity, Integer checked) {
        CartItem item = new CartItem();
        item.setId(id);
        item.setUserId(USER_ID);
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        item.setChecked(checked);
        return item;
    }

    private ProductSku sku(Long id, Long productId, Integer status, Integer stock) {
        return skuWithPrice(id, productId, status, stock, "10.00");
    }

    private ProductSku skuWithPrice(Long id, Long productId, Integer status, Integer stock, String price) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setProductId(productId);
        sku.setStatus(status);
        sku.setStock(stock);
        sku.setPrice(new BigDecimal(price));
        sku.setSpecs("规格" + id);
        return sku;
    }

    private Product product(Long id, Integer status) {
        Product product = new Product();
        product.setId(id);
        product.setName("测试商品");
        product.setMainImage("url-" + id);
        product.setStatus(status);
        return product;
    }
}
