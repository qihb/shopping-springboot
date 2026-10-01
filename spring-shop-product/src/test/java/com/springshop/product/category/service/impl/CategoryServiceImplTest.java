package com.springshop.product.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import com.springshop.product.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
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

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations =
            mock(ValueOperations.class);

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
    void should_tree_assemble_success() throws Exception {
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
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        List<CategoryNodeVO> tree = categoryService.tree();
        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("手机", tree.get(0).getChildren().get(0).getName());
    }

    @Test
    void tree_should_return_cache_when_hit() throws Exception {
        CategoryNodeVO cached = new CategoryNodeVO();
        cached.setId(1L);
        cached.setName("缓存分类");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.categoryTree())).thenReturn("[{\"id\":1}]");
        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenReturn(List.of(cached));

        List<CategoryNodeVO> tree = categoryService.tree();

        assertEquals("缓存分类", tree.get(0).getName());
        // 缓存命中时不应查库
        verify(categoryMapper, never()).selectList(any());
    }

    @Test
    void tree_should_query_db_and_fill_cache_when_miss() throws Exception {
        ProductCategory root = new ProductCategory();
        root.setId(1L);
        root.setParentId(0L);
        root.setName("数码");
        root.setSort(1);
        root.setStatus(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.categoryTree())).thenReturn(null);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root));
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        List<CategoryNodeVO> tree = categoryService.tree();

        assertEquals(1, tree.size());
        // 回填缓存，TTL 固定 1 小时
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(RedisKeys.categoryTree()), eq("[]"), ttlCaptor.capture());
        assertEquals(Duration.ofHours(1), ttlCaptor.getValue());
    }

    @Test
    void create_should_evict_tree_cache() {
        when(categoryMapper.insert(any(ProductCategory.class))).thenReturn(1);

        categoryService.create(request);

        // create 只触发 delete（不读 opsForValue），严格模式下无需多余 stub
        verify(stringRedisTemplate).delete(RedisKeys.categoryTree());
    }

    @Test
    void update_and_delete_should_evict_tree_cache() {
        // update 成功路径
        ProductCategory existing = new ProductCategory();
        existing.setId(1L);
        when(categoryMapper.selectById(1L)).thenReturn(existing);
        when(categoryMapper.updateById(any(ProductCategory.class))).thenReturn(1);
        categoryService.update(1L, request);
        verify(stringRedisTemplate).delete(RedisKeys.categoryTree());

        // delete 成功路径（无子分类、无商品）
        ProductCategory leaf = new ProductCategory();
        leaf.setId(2L);
        when(categoryMapper.selectById(2L)).thenReturn(leaf);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.selectCount(any())).thenReturn(0L);
        categoryService.delete(2L);
        verify(stringRedisTemplate, times(2)).delete(RedisKeys.categoryTree());
    }
}
