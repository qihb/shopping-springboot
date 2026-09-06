package com.springshop.product.category.service;

import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;

import java.util.List;

/**
 * 分类服务
 */
public interface CategoryService {

    void create(CategorySaveRequest request);

    void update(Long id, CategorySaveRequest request);

    void delete(Long id);

    CategoryVO getById(Long id);

    List<CategoryNodeVO> tree();
}
