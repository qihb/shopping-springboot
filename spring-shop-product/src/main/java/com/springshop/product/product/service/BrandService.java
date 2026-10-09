package com.springshop.product.product.service;

import com.springshop.product.product.dto.BrandSaveRequest;
import com.springshop.product.product.vo.BrandVO;

import java.util.List;

/**
 * 品牌服务（基础资料）
 */
public interface BrandService {

    /** 品牌列表（按 sort 升序，供下拉选择） */
    List<BrandVO> list();

    /** 品牌详情 */
    BrandVO getById(Long id);

    /** 新增品牌 */
    void create(BrandSaveRequest request);

    /** 修改品牌 */
    void update(Long id, BrandSaveRequest request);

    /** 删除品牌（品牌下存在商品时拒绝） */
    void delete(Long id);
}
