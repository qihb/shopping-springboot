package com.springshop.order.service;

import com.springshop.order.dto.AddressSaveRequest;
import com.springshop.order.vo.AddressVO;

import java.util.List;

/**
 * 收货地址服务
 *
 * <p>所有方法均以当前登录用户为边界，越权访问一律视为地址不存在。
 */
public interface AddressService {

    /**
     * 我的地址列表（默认地址排最前）
     */
    List<AddressVO> list(Long userId);

    /**
     * 新增地址，返回地址 id
     */
    Long create(Long userId, AddressSaveRequest request);

    /**
     * 修改地址（归属校验）
     */
    void update(Long userId, Long id, AddressSaveRequest request);

    /**
     * 设为默认地址
     */
    void setDefault(Long userId, Long id);

    /**
     * 删除地址（归属校验）
     */
    void delete(Long userId, Long id);
}
