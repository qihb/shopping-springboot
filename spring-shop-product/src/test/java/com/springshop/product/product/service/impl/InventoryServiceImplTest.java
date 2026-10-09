package com.springshop.product.product.service.impl;

import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.product.entity.Inventory;
import com.springshop.product.product.entity.InventoryLog;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.InventoryLogMapper;
import com.springshop.product.product.mapper.InventoryMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 库存服务单测：钉住「条件更新 + 同事务落流水」的契约
 *
 * <p>关键断言点：
 * <ol>
 *   <li>条件更新返回 0 行 ⇒ 抛业务异常，且<b>不写流水</b>；</li>
 *   <li>条件更新成功 ⇒ 流水的 before / after 必须由「回读值 ∓ 变更量」正确算出；</li>
 *   <li>流水的 {@code productId} 从 SKU 解析而来，不能为 null。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    private static final long SKU_ID = 1L;
    private static final long PRODUCT_ID = 10L;

    @Mock
    private InventoryMapper inventoryMapper;

    @Mock
    private InventoryLogMapper inventoryLogMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    @Test
    void lock_shouldWriteLog_withCorrectBeforeAfter() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.lockStock(SKU_ID, 3)).thenReturn(1);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 3));

        inventoryService.lock(SKU_ID, 3, "ORD-1");

        InventoryLog log = captureLog();
        assertEquals(InventoryLog.TYPE_LOCK, log.getChangeType());
        assertEquals(PRODUCT_ID, log.getProductId());
        assertEquals("ORD-1", log.getBizNo());
        assertEquals(100, log.getStockBefore(), "锁定不改在库量，前后应相同");
        assertEquals(100, log.getStockAfter());
        assertEquals(0, log.getLockedBefore(), "锁定前 = 锁定后 - 本次锁定量");
        assertEquals(3, log.getLockedAfter());
    }

    @Test
    void lock_shouldThrowAndNotWriteLog_whenAvailableInsufficient() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.lockStock(SKU_ID, 999)).thenReturn(0);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.lock(SKU_ID, 999, "ORD-1"));

        assertEquals(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT.getCode(), ex.getCode());
        verify(inventoryLogMapper, never()).insert(any(InventoryLog.class));
    }

    @Test
    void lock_shouldThrowNotFound_whenInventoryRowMissing() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.lockStock(SKU_ID, 1)).thenReturn(0);
        when(inventoryMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.lock(SKU_ID, 1, "ORD-1"));

        assertEquals(ResultCode.PRODUCT_INVENTORY_NOT_FOUND.getCode(), ex.getCode());
        verify(inventoryLogMapper, never()).insert(any(InventoryLog.class));
    }

    @Test
    void outbound_shouldReduceBothStockAndLocked() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.outbound(SKU_ID, 3)).thenReturn(1);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(97, 0));

        inventoryService.outbound(SKU_ID, 3, "ORD-1");

        InventoryLog log = captureLog();
        assertEquals(InventoryLog.TYPE_OUTBOUND, log.getChangeType());
        assertEquals(100, log.getStockBefore(), "出库前在库 = 出库后 + 本次出库量");
        assertEquals(97, log.getStockAfter());
        assertEquals(3, log.getLockedBefore(), "出库同时释放锁定");
        assertEquals(0, log.getLockedAfter());
    }

    /**
     * 出库<b>不应</b>失效详情缓存：可售量 = stock − locked，出库时两者同时减 q ⇒ 差值不变。
     *
     * <p>这是刻意的设计而不是遗漏 —— 用一条断言把它钉住，免得后人为了「对称」加一次无谓的
     * Redis 删除（还平白给支付热路径引入 Redis 依赖）。
     */
    @Test
    void outbound_shouldNotEvictDetailCache_becauseAvailableIsInvariant() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.outbound(SKU_ID, 3)).thenReturn(1);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(97, 0));

        inventoryService.outbound(SKU_ID, 3, "ORD-1");

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void release_shouldReturnLockedToAvailable() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.releaseLock(SKU_ID, 3)).thenReturn(1);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 0));

        inventoryService.release(SKU_ID, 3, "ORD-1");

        InventoryLog log = captureLog();
        assertEquals(InventoryLog.TYPE_RELEASE, log.getChangeType());
        assertEquals(100, log.getStockBefore(), "释放不改在库量");
        assertEquals(100, log.getStockAfter());
        assertEquals(3, log.getLockedBefore());
        assertEquals(0, log.getLockedAfter());
    }

    @Test
    void release_shouldThrow_whenLockInsufficient() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.releaseLock(SKU_ID, 5)).thenReturn(0);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.release(SKU_ID, 5, "ORD-1"));

        assertEquals(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT.getCode(), ex.getCode());
        verify(inventoryLogMapper, never()).insert(any(InventoryLog.class));
    }

    @Test
    void adjust_shouldWriteLog_withOldAndNewStock() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.selectOne(any()))
                .thenReturn(inventory(100, 5))   // 调整前
                .thenReturn(inventory(80, 5));   // 调整后
        when(inventoryMapper.adjustStock(SKU_ID, 80)).thenReturn(1);

        inventoryService.adjust(SKU_ID, 80, 999L, "盘点差异");

        InventoryLog log = captureLog();
        assertEquals(InventoryLog.TYPE_ADJUST, log.getChangeType());
        assertEquals(100, log.getStockBefore());
        assertEquals(80, log.getStockAfter());
        assertEquals(5, log.getLockedBefore(), "调整在库量不影响锁定量");
        assertEquals(5, log.getLockedAfter());
        assertEquals(999L, log.getOperatorId());
        assertEquals("盘点差异", log.getRemark());
        // 调整改变了可售量，必须失效商品详情缓存（这条路径没有调用方兜底）
        verify(stringRedisTemplate).delete(RedisKeys.productDetail(PRODUCT_ID));
    }

    @Test
    void adjust_shouldThrow_whenNewStockBelowLocked() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 5));
        when(inventoryMapper.adjustStock(SKU_ID, 3)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.adjust(SKU_ID, 3, 999L, null));

        assertEquals(ResultCode.PRODUCT_INVENTORY_LOCKED_CONFLICT.getCode(), ex.getCode());
        verify(inventoryLogMapper, never()).insert(any(InventoryLog.class));
        // 调整失败 ⇒ 库存没变 ⇒ 不该白白失效缓存
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void initStock_shouldInsertRowAndLog() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(sku());

        inventoryService.initStock(SKU_ID, 50);

        verify(inventoryMapper).insert(any(Inventory.class));
        InventoryLog log = captureLog();
        assertEquals(InventoryLog.TYPE_INIT, log.getChangeType());
        assertEquals(0, log.getStockBefore());
        assertEquals(50, log.getStockAfter());
        assertEquals(0, log.getLockedBefore());
        assertEquals(0, log.getLockedAfter());
    }

    @Test
    void anyWrite_shouldThrow_whenSkuMissing() {
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.lock(SKU_ID, 1, "ORD-1"));

        assertEquals(ResultCode.PRODUCT_SKU_NOT_FOUND.getCode(), ex.getCode());
        verify(inventoryMapper, never()).lockStock(anyLong(), any());
    }

    private InventoryLog captureLog() {
        ArgumentCaptor<InventoryLog> captor = ArgumentCaptor.forClass(InventoryLog.class);
        verify(inventoryLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private Inventory inventory(int stock, int lockedStock) {
        Inventory inventory = new Inventory();
        inventory.setSkuId(SKU_ID);
        inventory.setStock(stock);
        inventory.setLockedStock(lockedStock);
        return inventory;
    }

    private ProductSku sku() {
        ProductSku sku = new ProductSku();
        sku.setId(SKU_ID);
        sku.setProductId(PRODUCT_ID);
        return sku;
    }
}
