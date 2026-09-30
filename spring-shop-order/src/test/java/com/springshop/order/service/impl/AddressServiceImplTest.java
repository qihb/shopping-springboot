package com.springshop.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.order.dto.AddressSaveRequest;
import com.springshop.order.entity.ShippingAddress;
import com.springshop.order.mapper.ShippingAddressMapper;
import com.springshop.order.vo.AddressVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收货地址服务单元测试（纯 Mockito，不启动 Spring 容器）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceImplTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ShippingAddressMapper shippingAddressMapper;

    @InjectMocks
    private AddressServiceImpl addressService;

    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { ShippingAddress.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @Test
    void create_should_clear_other_default_when_is_default_true() {
        when(shippingAddressMapper.insert(any(ShippingAddress.class))).thenAnswer(invocation -> {
            ShippingAddress entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        });

        Long id = addressService.create(USER_ID, saveRequest(true));

        // 设为默认前先把该用户其他地址取消默认
        verify(shippingAddressMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<ShippingAddress> captor = ArgumentCaptor.forClass(ShippingAddress.class);
        verify(shippingAddressMapper).insert(captor.capture());
        ShippingAddress saved = captor.getValue();
        assertEquals(100L, id);
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(1, saved.getIsDefault());
        assertEquals("张三", saved.getReceiverName());
    }

    @Test
    void create_should_not_clear_default_when_is_default_false() {
        addressService.create(USER_ID, saveRequest(false));

        ArgumentCaptor<ShippingAddress> captor = ArgumentCaptor.forClass(ShippingAddress.class);
        verify(shippingAddressMapper).insert(captor.capture());
        verify(shippingAddressMapper, never()).update(any(), any());
        assertEquals(0, captor.getValue().getIsDefault());
        assertEquals(USER_ID, captor.getValue().getUserId());
    }

    @Test
    void list_should_return_default_first() {
        when(shippingAddressMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                address(1L, USER_ID, 1),
                address(2L, USER_ID, 0)
        ));

        List<AddressVO> result = addressService.list(USER_ID);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getIsDefault());
        assertEquals(1L, result.get(0).getId());
        assertFalse(result.get(1).getIsDefault());
        assertEquals(2L, result.get(1).getId());
    }

    @Test
    void update_should_fail_when_address_not_owned() {
        when(shippingAddressMapper.selectById(9L)).thenReturn(address(9L, 999L, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> addressService.update(USER_ID, 9L, saveRequest(false)));
        assertEquals(ResultCode.ORDER_ADDRESS_NOT_FOUND.getCode(), ex.getCode());
        verify(shippingAddressMapper, never()).updateById(any(ShippingAddress.class));
    }

    @Test
    void set_default_should_clear_old_default_then_set_new() {
        when(shippingAddressMapper.selectById(9L)).thenReturn(address(9L, USER_ID, 0));

        addressService.setDefault(USER_ID, 9L);

        InOrder inOrder = inOrder(shippingAddressMapper);
        inOrder.verify(shippingAddressMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<ShippingAddress> captor = ArgumentCaptor.forClass(ShippingAddress.class);
        inOrder.verify(shippingAddressMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getIsDefault());
        assertEquals(9L, captor.getValue().getId());
    }

    @Test
    void delete_should_fail_when_address_not_found() {
        when(shippingAddressMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> addressService.delete(USER_ID, 9L));
        assertEquals(ResultCode.ORDER_ADDRESS_NOT_FOUND.getCode(), ex.getCode());
        verify(shippingAddressMapper, never()).deleteById(9L);
    }

    // ---------------- 构造辅助 ----------------

    private AddressSaveRequest saveRequest(Boolean isDefault) {
        AddressSaveRequest request = new AddressSaveRequest();
        request.setReceiverName("张三");
        request.setReceiverPhone("13800138000");
        request.setProvince("广东省");
        request.setCity("深圳市");
        request.setDistrict("南山区");
        request.setDetailAddress("科技园路 1 号");
        request.setIsDefault(isDefault);
        return request;
    }

    private ShippingAddress address(Long id, Long userId, Integer isDefault) {
        ShippingAddress address = new ShippingAddress();
        address.setId(id);
        address.setUserId(userId);
        address.setReceiverName("张三");
        address.setReceiverPhone("13800138000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("科技园路 1 号");
        address.setIsDefault(isDefault);
        return address;
    }
}
