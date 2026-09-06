package com.springshop.product.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import com.springshop.product.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 分类服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private ProductCategoryMapper categoryMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private CategorySaveRequest request;

    @BeforeEach
    void setUp() {
        request = new CategorySaveRequest();
        request.setParentId(0L);
        request.setName("数码");
        request.setSort(1);
        request.setStatus(1);
    }

    @Test
    void should_create_success() {
        when(categoryMapper.insert(any(ProductCategory.class))).thenReturn(1);

        assertDoesNotThrow(() -> categoryService.create(request));
        verify(categoryMapper, times(1)).insert(any(ProductCategory.class));
    }

    @Test
    void should_update_throw_when_category_not_found() {
        when(categoryMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> categoryService.update(99L, request));
        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_delete_throw_when_has_children() {
        ProductCategory category = new ProductCategory();
        category.setId(1L);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> categoryService.delete(1L));
        assertEquals(ResultCode.PRODUCT_CATEGORY_HAS_CHILDREN.getCode(), ex.getCode());
    }

    @Test
    void should_get_by_id_throw_when_missing() {
        when(categoryMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> categoryService.getById(1L));
        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_tree_assemble_success() {
        ProductCategory root = new ProductCategory();
        root.setId(1L);
        root.setParentId(0L);
        root.setName("数码");
        root.setSort(1);
        root.setStatus(1);

        ProductCategory child = new ProductCategory();
        child.setId(2L);
        child.setParentId(1L);
        child.setName("手机");
        child.setSort(0);
        child.setStatus(1);

        when(categoryMapper.selectList(any())).thenReturn(List.of(root, child));

        List<CategoryNodeVO> tree = categoryService.tree();
        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("手机", tree.get(0).getChildren().get(0).getName());
    }
}
