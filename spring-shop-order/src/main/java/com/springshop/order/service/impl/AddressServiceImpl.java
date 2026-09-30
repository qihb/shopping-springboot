package com.springshop.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.order.dto.AddressSaveRequest;
import com.springshop.order.entity.ShippingAddress;
import com.springshop.order.mapper.ShippingAddressMapper;
import com.springshop.order.service.AddressService;
import com.springshop.order.vo.AddressVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 收货地址服务实现
 *
 * <p>默认地址全局唯一：设为默认时先清空该用户其他地址的默认标记，再落库。
 */
@Service
public class AddressServiceImpl implements AddressService {

    private final ShippingAddressMapper shippingAddressMapper;

    public AddressServiceImpl(ShippingAddressMapper shippingAddressMapper) {
        this.shippingAddressMapper = shippingAddressMapper;
    }

    @Override
    public List<AddressVO> list(Long userId) {
        List<ShippingAddress> addresses = shippingAddressMapper.selectList(new LambdaQueryWrapper<ShippingAddress>()
                .eq(ShippingAddress::getUserId, userId)
                .orderByDesc(ShippingAddress::getIsDefault)
                .orderByDesc(ShippingAddress::getId));
        return addresses.stream().map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, AddressSaveRequest request) {
        ShippingAddress address = new ShippingAddress();
        address.setUserId(userId);
        applyRequest(address, request);
        boolean isDefault = Boolean.TRUE.equals(request.getIsDefault());
        address.setIsDefault(isDefault ? 1 : 0);
        if (isDefault) {
            clearDefault(userId);
        }
        shippingAddressMapper.insert(address);
        return address.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long id, AddressSaveRequest request) {
        ShippingAddress address = requireOwned(userId, id);
        applyRequest(address, request);
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefault(userId);
            address.setIsDefault(1);
        } else {
            address.setIsDefault(0);
        }
        shippingAddressMapper.updateById(address);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long id) {
        ShippingAddress address = requireOwned(userId, id);
        clearDefault(userId);
        address.setIsDefault(1);
        shippingAddressMapper.updateById(address);
    }

    @Override
    public void delete(Long userId, Long id) {
        requireOwned(userId, id);
        shippingAddressMapper.deleteById(id);
    }

    /**
     * 取出属于当前用户的地址，不存在或归属他人一律视为不存在，避免越权操作
     */
    private ShippingAddress requireOwned(Long userId, Long id) {
        ShippingAddress address = shippingAddressMapper.selectById(id);
        if (address == null || !Objects.equals(address.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_ADDRESS_NOT_FOUND);
        }
        return address;
    }

    /**
     * 清空该用户已存在的默认标记（保证默认地址唯一）
     */
    private void clearDefault(Long userId) {
        shippingAddressMapper.update(null, new LambdaUpdateWrapper<ShippingAddress>()
                .eq(ShippingAddress::getUserId, userId)
                .eq(ShippingAddress::getIsDefault, 1)
                .set(ShippingAddress::getIsDefault, 0));
    }

    private void applyRequest(ShippingAddress address, AddressSaveRequest request) {
        address.setReceiverName(request.getReceiverName());
        address.setReceiverPhone(request.getReceiverPhone());
        address.setProvince(request.getProvince());
        address.setCity(request.getCity());
        address.setDistrict(request.getDistrict());
        address.setDetailAddress(request.getDetailAddress());
    }

    private AddressVO toVO(ShippingAddress address) {
        AddressVO vo = new AddressVO();
        vo.setId(address.getId());
        vo.setReceiverName(address.getReceiverName());
        vo.setReceiverPhone(address.getReceiverPhone());
        vo.setProvince(address.getProvince());
        vo.setCity(address.getCity());
        vo.setDistrict(address.getDistrict());
        vo.setDetailAddress(address.getDetailAddress());
        vo.setIsDefault(Objects.equals(address.getIsDefault(), 1));
        return vo;
    }
}
