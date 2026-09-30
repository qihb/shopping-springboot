package com.springshop.cart.service;

import com.springshop.cart.dto.CartAddRequest;
import com.springshop.cart.dto.CartCheckedRequest;
import com.springshop.cart.dto.CartQuantityRequest;
import com.springshop.cart.vo.CartVO;

/**
 * 购物车服务
 */
public interface CartService {

    /**
     * 加购：同 SKU 已存在则累加数量并置为勾选
     */
    void add(Long userId, CartAddRequest request);

    /**
     * 购物车列表（含失效标记与汇总金额）
     */
    CartVO list(Long userId);

    /**
     * 修改条目数量
     */
    void updateQuantity(Long userId, Long id, CartQuantityRequest request);

    /**
     * 单条勾选 / 取消勾选
     */
    void updateChecked(Long userId, Long id, CartCheckedRequest request);

    /**
     * 全选 / 全不选
     */
    void updateAllChecked(Long userId, CartCheckedRequest request);

    /**
     * 删除单条
     */
    void delete(Long userId, Long id);

    /**
     * 删除已勾选条目
     */
    void deleteChecked(Long userId);

    /**
     * 清空购物车
     */
    void clear(Long userId);
}
