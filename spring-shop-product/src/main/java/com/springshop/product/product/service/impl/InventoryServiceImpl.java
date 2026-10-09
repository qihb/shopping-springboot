package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.product.dto.InventoryLogQuery;
import com.springshop.product.product.entity.Inventory;
import com.springshop.product.product.entity.InventoryLog;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.InventoryLogMapper;
import com.springshop.product.product.mapper.InventoryMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import com.springshop.product.product.vo.InventoryLogVO;
import com.springshop.product.product.vo.InventoryVO;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 库存服务实现
 *
 * <p>核心不变量：<b>条件更新成功 ⇒ 必落一条流水</b>。
 * 条件更新由数据库保证原子性；流水与业务更新在同一事务内，二者同生共死。
 *
 * <p><b>详情缓存失效的责任边界（易漏）</b>：商品详情缓存 {@code product:detail:{id}}
 * 里含每个 SKU 的<b>可售量</b>，所以凡会改变可售量的库存变更都必须失效它。
 * 现状分工：
 * <ul>
 *   <li>{@link #lock} / {@link #release}：可售量会变，但调用方
 *       {@code OrderServiceImpl} 在 create / cancel / systemCancel 里已经失效了，本类<b>不重复做</b>；</li>
 *   <li>{@link #outbound}：<b>可售量不变</b> —— {@code stock} 与 {@code locked_stock} 同时减 q，
 *       差值恒等（支付只是把「锁定」转成「已售」）⇒ 天然无需失效，
 *       <b>不要为了「对称」给它加失效</b>；</li>
 *   <li>{@link #adjust}：只有它没有调用方兜底，因此<b>在本类内失效</b>
 *       （与 {@code ProductManageServiceImpl} 失效详情缓存的写法一致）。</li>
 * </ul>
 */
@Service
public class InventoryServiceImpl implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final InventoryLogMapper inventoryLogMapper;
    private final ProductSkuMapper productSkuMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public InventoryServiceImpl(InventoryMapper inventoryMapper,
                                InventoryLogMapper inventoryLogMapper,
                                ProductSkuMapper productSkuMapper,
                                StringRedisTemplate stringRedisTemplate) {
        this.inventoryMapper = inventoryMapper;
        this.inventoryLogMapper = inventoryLogMapper;
        this.productSkuMapper = productSkuMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initStock(Long skuId, int quantity) {
        Long productId = resolveProductId(skuId);

        Inventory inventory = new Inventory();
        inventory.setSkuId(skuId);
        inventory.setStock(quantity);
        inventory.setLockedStock(0);
        inventoryMapper.insert(inventory);

        InventoryLog log = newLog(skuId, productId, InventoryLog.TYPE_INIT, null, null, null);
        log.setStockBefore(0);
        log.setStockAfter(quantity);
        log.setLockedBefore(0);
        log.setLockedAfter(0);
        inventoryLogMapper.insert(log);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lock(Long skuId, int quantity, String bizNo) {
        Long productId = resolveProductId(skuId);

        int rows = inventoryMapper.lockStock(skuId, quantity);
        if (rows == 0) {
            requireInventory(skuId);
            throw new BusinessException(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT);
        }

        Inventory after = requireInventory(skuId);
        InventoryLog log = newLog(skuId, productId, InventoryLog.TYPE_LOCK, bizNo, null, null);
        log.setStockBefore(after.getStock());
        log.setStockAfter(after.getStock());
        log.setLockedBefore(after.getLockedStock() - quantity);
        log.setLockedAfter(after.getLockedStock());
        inventoryLogMapper.insert(log);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void outbound(Long skuId, int quantity, String bizNo) {
        Long productId = resolveProductId(skuId);

        int rows = inventoryMapper.outbound(skuId, quantity);
        if (rows == 0) {
            requireInventory(skuId);
            throw new BusinessException(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT);
        }

        Inventory after = requireInventory(skuId);
        InventoryLog log = newLog(skuId, productId, InventoryLog.TYPE_OUTBOUND, bizNo, null, null);
        log.setStockBefore(after.getStock() + quantity);
        log.setStockAfter(after.getStock());
        log.setLockedBefore(after.getLockedStock() + quantity);
        log.setLockedAfter(after.getLockedStock());
        inventoryLogMapper.insert(log);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long skuId, int quantity, String bizNo) {
        Long productId = resolveProductId(skuId);

        int rows = inventoryMapper.releaseLock(skuId, quantity);
        if (rows == 0) {
            requireInventory(skuId);
            throw new BusinessException(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT);
        }

        Inventory after = requireInventory(skuId);
        InventoryLog log = newLog(skuId, productId, InventoryLog.TYPE_RELEASE, bizNo, null, null);
        log.setStockBefore(after.getStock());
        log.setStockAfter(after.getStock());
        log.setLockedBefore(after.getLockedStock() + quantity);
        log.setLockedAfter(after.getLockedStock());
        inventoryLogMapper.insert(log);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjust(Long skuId, int newStock, Long operatorId, String remark) {
        Long productId = resolveProductId(skuId);

        Inventory before = requireInventory(skuId);

        int rows = inventoryMapper.adjustStock(skuId, newStock);
        if (rows == 0) {
            requireInventory(skuId);
            throw new BusinessException(ResultCode.PRODUCT_INVENTORY_LOCKED_CONFLICT);
        }

        Inventory after = requireInventory(skuId);
        InventoryLog log = newLog(skuId, productId, InventoryLog.TYPE_ADJUST, null, operatorId, remark);
        log.setStockBefore(before.getStock());
        log.setStockAfter(after.getStock());
        log.setLockedBefore(after.getLockedStock());
        log.setLockedAfter(after.getLockedStock());
        inventoryLogMapper.insert(log);

        // 调整在库量会改变可售量，而商品详情缓存里带着每个 SKU 的可售量（TTL 30 分钟 + 抖动）。
        // 这条路径没有调用方兜底失效（lock/release 由 OrderServiceImpl 失效），必须自己来，
        // 否则后台改完库存，前台详情页最多 31 分钟仍显示旧数字。
        // 与 ProductManageServiceImpl 失效详情缓存保持同一写法（直删、不吞异常）。
        stringRedisTemplate.delete(RedisKeys.productDetail(productId));
    }

    @Override
    public InventoryVO getBySkuId(Long skuId) {
        Inventory inventory = requireInventory(skuId);
        return new InventoryVO(inventory.getSkuId(), inventory.getStock(), inventory.getLockedStock());
    }

    @Override
    public int available(Long skuId) {
        return availableOf(inventoryMapper.selectOne(
                Wrappers.<Inventory>lambdaQuery().eq(Inventory::getSkuId, skuId)));
    }

    @Override
    public Map<Long, Integer> availableMap(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return inventoryMapper.selectList(
                        Wrappers.<Inventory>lambdaQuery().in(Inventory::getSkuId, distinctIds))
                .stream()
                .collect(Collectors.toMap(Inventory::getSkuId, this::availableOf));
    }

    @Override
    public PageResult<InventoryLogVO> pageLogs(Long skuId, InventoryLogQuery query) {
        Page<InventoryLog> page = query.toPage();

        LambdaQueryWrapper<InventoryLog> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(InventoryLog::getSkuId, skuId);
        if (query.getChangeType() != null) {
            wrapper.eq(InventoryLog::getChangeType, query.getChangeType());
        }
        if (StringUtils.hasText(query.getBizNo())) {
            wrapper.eq(InventoryLog::getBizNo, query.getBizNo());
        }
        if (query.getStartDate() != null) {
            wrapper.ge(InventoryLog::getCreateTime, query.getStartDate().atStartOfDay());
        }
        if (query.getEndDate() != null) {
            wrapper.lt(InventoryLog::getCreateTime, query.getEndDate().plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(InventoryLog::getId);

        IPage<InventoryLog> result = inventoryLogMapper.selectPage(page, wrapper);
        return PageResult.of(result.convert(this::toVO));
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 可售量 = 在库 − 锁定，最小 0；库存行缺失（null）时按 0 处理（读路径 fail-closed）
     */
    private int availableOf(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int stock = inventory.getStock() == null ? 0 : inventory.getStock();
        int locked = inventory.getLockedStock() == null ? 0 : inventory.getLockedStock();
        return Math.max(stock - locked, 0);
    }

    /**
     * 回读库存行（走 MyBatis-Plus，自动过滤逻辑删除）
     */
    private Inventory requireInventory(Long skuId) {
        Inventory inventory = inventoryMapper.selectOne(
                Wrappers.<Inventory>lambdaQuery().eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new BusinessException(ResultCode.PRODUCT_INVENTORY_NOT_FOUND);
        }
        return inventory;
    }

    /**
     * 解析 SKU 所属商品 id（流水冗余该字段，便于按商品维度查）
     */
    private Long resolveProductId(Long skuId) {
        ProductSku sku = productSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_NOT_FOUND);
        }
        return sku.getProductId();
    }

    private InventoryLog newLog(Long skuId, Long productId, int changeType,
                                String bizNo, Long operatorId, String remark) {
        InventoryLog log = new InventoryLog();
        log.setSkuId(skuId);
        log.setProductId(productId);
        log.setChangeType(changeType);
        log.setBizNo(bizNo);
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        return log;
    }

    private InventoryLogVO toVO(InventoryLog log) {
        InventoryLogVO vo = new InventoryLogVO();
        BeanUtils.copyProperties(log, vo);
        return vo;
    }
}
