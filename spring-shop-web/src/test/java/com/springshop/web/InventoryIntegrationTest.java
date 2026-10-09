package com.springshop.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.product.entity.Inventory;
import com.springshop.product.product.entity.InventoryLog;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.InventoryLogMapper;
import com.springshop.product.product.mapper.InventoryMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存域集成测试（真连 H2，跑真实的条件更新与流水落库）
 *
 * <p>为什么必须有这一层：{@link com.springshop.product.product.service.impl.InventoryServiceImplTest}
 * 是纯 Mockito，mapper 被 mock 掉，<b>条件更新的 SQL 正确性根本不会被执行到</b>。
 * 这里用真库把「条件更新 + 流水」整条链路跑一遍，并做<b>对账</b>：
 * 最后一条流水的 after 必须等于当前库存，且相邻流水首尾相接。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private InventoryLogMapper inventoryLogMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void lockThenOutbound_shouldKeepLedgerReconciled() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);
        inventoryService.lock(skuId, 30, "ORD-1");
        inventoryService.outbound(skuId, 30, "ORD-1");

        Inventory now = current(skuId);
        assertEquals(70, now.getStock(), "出库后在库量应扣减");
        assertEquals(0, now.getLockedStock(), "出库后锁定量应归零");

        List<InventoryLog> logs = logsOf(skuId);
        assertEquals(3, logs.size(), "初始化 / 锁定 / 出库 各一条流水");
        assertEquals(InventoryLog.TYPE_INIT, logs.get(0).getChangeType());
        assertEquals(InventoryLog.TYPE_LOCK, logs.get(1).getChangeType());
        assertEquals(InventoryLog.TYPE_OUTBOUND, logs.get(2).getChangeType());

        // 对账 1：相邻流水首尾相接
        for (int i = 1; i < logs.size(); i++) {
            assertEquals(logs.get(i - 1).getStockAfter(), logs.get(i).getStockBefore(),
                    "流水链不连续（stock）：第 " + i + " 条");
            assertEquals(logs.get(i - 1).getLockedAfter(), logs.get(i).getLockedBefore(),
                    "流水链不连续（locked）：第 " + i + " 条");
        }
        // 对账 2：最后一条的 after == 当前值
        InventoryLog last = logs.get(logs.size() - 1);
        assertEquals(last.getStockAfter(), now.getStock(), "最后一条流水的 after 应等于当前在库量");
        assertEquals(last.getLockedAfter(), now.getLockedStock(), "最后一条流水的 after 应等于当前锁定量");
    }

    @Test
    void lock_shouldRejectOversell() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.lock(skuId, 101, "ORD-1"));
        assertEquals(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT.getCode(), ex.getCode());

        Inventory now = current(skuId);
        assertEquals(100, now.getStock());
        assertEquals(0, now.getLockedStock(), "失败的锁定不能留下痕迹");
    }

    @Test
    void lock_shouldCountLockedAsUnavailable() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);
        inventoryService.lock(skuId, 60, "ORD-1");

        // 可售只剩 40，再锁 41 必须失败
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.lock(skuId, 41, "ORD-2"));
        assertEquals(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT.getCode(), ex.getCode());

        assertEquals(100, current(skuId).getStock(), "锁定不改在库量");
        assertEquals(60, current(skuId).getLockedStock());
    }

    @Test
    void release_shouldReturnLockedToAvailable() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);
        inventoryService.lock(skuId, 30, "ORD-1");
        inventoryService.release(skuId, 30, "ORD-1");

        Inventory now = current(skuId);
        assertEquals(100, now.getStock());
        assertEquals(0, now.getLockedStock());

        // 释放后 100 又可售
        inventoryService.lock(skuId, 100, "ORD-2");
        assertEquals(100, current(skuId).getLockedStock());
    }

    @Test
    void release_shouldRejectDoubleRelease() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);
        inventoryService.lock(skuId, 30, "ORD-1");
        inventoryService.release(skuId, 30, "ORD-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.release(skuId, 30, "ORD-1"));
        assertEquals(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT.getCode(), ex.getCode(),
                "重复释放必须被条件更新挡住（防超卖）");
    }

    @Test
    void adjust_shouldRejectValueBelowLocked() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);
        inventoryService.lock(skuId, 60, "ORD-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.adjust(skuId, 50, 999L, "盘点"));
        assertEquals(ResultCode.PRODUCT_INVENTORY_LOCKED_CONFLICT.getCode(), ex.getCode());
        assertEquals(100, current(skuId).getStock(), "失败的调整不能改动库存");
    }

    @Test
    void adjust_shouldWriteOperatorAndRemark() {
        long skuId = insertSku();
        inventoryService.initStock(skuId, 100);
        inventoryService.adjust(skuId, 80, 999L, "盘点差异");

        InventoryLog last = logsOf(skuId).get(1);
        assertEquals(InventoryLog.TYPE_ADJUST, last.getChangeType());
        assertEquals(999L, last.getOperatorId());
        assertEquals("盘点差异", last.getRemark());
        assertEquals(100, last.getStockBefore());
        assertEquals(80, last.getStockAfter());
        assertEquals(80, current(skuId).getStock());

        // 详情缓存里带着每个 SKU 的可售量，调整改变了它 ⇒ 必须失效。
        // 这里走真库，能顺带验证 key 里用的是「SKU 解析出的 productId」而不是 skuId（写错就查不到缓存）。
        verify(stringRedisTemplate).delete(RedisKeys.productDetail(1L));
    }

    @Test
    void adminInventoryApi_shouldRequireLogin() throws Exception {
        mockMvc.perform(get("/api/admin/inventory/skus/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/inventory/skus/1/logs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private long insertSku() {
        ProductSku sku = new ProductSku();
        sku.setProductId(1L);
        sku.setSkuCode("SKU-INV-" + System.nanoTime());
        sku.setSpecs("颜色:黑");
        sku.setPrice(new BigDecimal("19.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);
        return sku.getId();
    }

    private Inventory current(long skuId) {
        return inventoryMapper.selectOne(
                Wrappers.<Inventory>lambdaQuery().eq(Inventory::getSkuId, skuId));
    }

    private List<InventoryLog> logsOf(long skuId) {
        return inventoryLogMapper.selectList(Wrappers.<InventoryLog>lambdaQuery()
                .eq(InventoryLog::getSkuId, skuId)
                .orderByAsc(InventoryLog::getId));
    }
}
