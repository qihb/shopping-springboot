package com.springshop.product.product.service;

import com.springshop.product.product.dto.AttributeSaveRequest;
import com.springshop.product.product.dto.AttributeValueSaveRequest;
import com.springshop.product.product.vo.AttributeVO;
import com.springshop.product.product.vo.AttributeValueVO;

import java.util.List;

/**
 * 商品属性（规格体系）服务
 *
 * <p>属性挂在分类下（如「颜色」「尺寸」），属性下挂受控可选值（如 黑 / 白 / L / XL）。
 * SKU 通过 {@code sku_spec_value} 关联到具体可选值，从而支撑「SPU 管展示、SKU 管交易」。
 */
public interface AttributeService {

    /** 按分类查询属性（含可选值列表，按 sort 升序） */
    List<AttributeVO> listByCategory(Long categoryId);

    /** 属性详情（含可选值列表） */
    AttributeVO getById(Long id);

    /** 新增属性 */
    void create(AttributeSaveRequest request);

    /** 修改属性 */
    void update(Long id, AttributeSaveRequest request);

    /** 删除属性（存在可选值或已被 SKU 使用时拒绝） */
    void delete(Long id);

    /** 在属性下新增可选值 */
    AttributeValueVO addValue(Long attributeId, AttributeValueSaveRequest request);

    /** 修改可选值 */
    void updateValue(Long attributeId, Long valueId, AttributeValueSaveRequest request);

    /** 删除可选值（已被 SKU 使用时拒绝） */
    void deleteValue(Long attributeId, Long valueId);
}
