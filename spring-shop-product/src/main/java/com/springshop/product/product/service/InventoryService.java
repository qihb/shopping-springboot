package com.springshop.product.product.service;

import com.springshop.common.result.PageResult;
import com.springshop.product.product.dto.InventoryLogQuery;
import com.springshop.product.product.vo.InventoryLogVO;
import com.springshop.product.product.vo.InventoryVO;

import java.util.Collection;
import java.util.Map;

/**
 * 库存服务
 *
 * <p>三量语义：{@code stock}（在库实物量）/ {@code lockedStock}（未付款订单锁定）/
 * {@code available = stock - lockedStock}（可售量）。
 *
 * <p><b>每个写方法都是「条件更新 + 同事务落流水」</b>：
 * 先由数据库条件更新保证原子性（0 行即条件不满足，抛业务异常），
 * 更新成功后在<b>同一事务内</b>回读该行、算出变更前后值并写 {@code inventory_log}。
 * 因此调用方若在事务中（如下单），流水与业务变更同生共死 —— 保证「改了库存必有账」。
 */
public interface InventoryService {

    /**
     * 初始化库存行（商品创建 / 批量导入用），并落一条「导入初始化」流水
     *
     * @param skuId    刚创建的 SKU id
     * @param quantity 初始在库量
     */
    void initStock(Long skuId, int quantity);

    /**
     * 下单锁定：把 quantity 从「可售」挪到「锁定」，不动在库量
     *
     * @throws com.springshop.common.exception.BusinessException 可售量不足时
     */
    void lock(Long skuId, int quantity, String bizNo);

    /**
     * 支付出库：锁定转已售，在库量与锁定量同时扣减
     *
     * @throws com.springshop.common.exception.BusinessException 锁定量不足时（防重复出库）
     */
    void outbound(Long skuId, int quantity, String bizNo);

    /**
     * 取消 / 超时释放锁定：把 quantity 从「锁定」挪回「可售」
     *
     * @throws com.springshop.common.exception.BusinessException 锁定量不足时（防重复释放）
     */
    void release(Long skuId, int quantity, String bizNo);

    /**
     * 后台调整在库量（绝对赋值），不允许调到低于当前锁定量
     *
     * @param operatorId 操作管理员 id
     */
    void adjust(Long skuId, int newStock, Long operatorId, String remark);

    /**
     * 查单个 SKU 的库存三量
     *
     * @throws com.springshop.common.exception.BusinessException 库存行不存在时
     */
    InventoryVO getBySkuId(Long skuId);

    /**
     * 查可售量（{@code stock - lockedStock}），供加购校验、商品详情等<b>读路径</b>使用
     *
     * <p>与 {@link #getBySkuId(Long)} 的差别：<b>库存行不存在时返回 0 而不是抛异常</b>。
     * 读路径要 fail-closed —— 查不到库存就当作不可售，既不会超卖，
     * 也不会因为一条脏数据把整个商品详情页打成 500。
     *
     * @return 可售量，最小 0（负数按 0 返回）
     */
    int available(Long skuId);

    /**
     * 批量查可售量，避免列表 / 购物车场景逐条查询产生 N+1
     *
     * @return skuId → 可售量；<b>没有库存行的 skuId 不会出现在结果里</b>，调用方按 0 处理
     */
    Map<Long, Integer> availableMap(Collection<Long> skuIds);

    /**
     * 分页查某 SKU 的库存流水（按 id 倒序）
     */
    PageResult<InventoryLogVO> pageLogs(Long skuId, InventoryLogQuery query);
}
