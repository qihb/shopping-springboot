package com.springshop.product.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分类服务实现
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private final ProductCategoryMapper categoryMapper;

    private final ProductMapper productMapper;

    public CategoryServiceImpl(ProductCategoryMapper categoryMapper, ProductMapper productMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
    }

    @Override
    public void create(CategorySaveRequest request) {
        ProductCategory category = new ProductCategory();
        apply(category, request);
        if (category.getSort() == null) {
            category.setSort(0);
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        categoryMapper.insert(category);
    }

    @Override
    public void update(Long id, CategorySaveRequest request) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        if (request.getParentId() != null
                && (request.getParentId().equals(id) || isDescendant(id, request.getParentId()))) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        apply(category, request);
        categoryMapper.updateById(category);
    }

    @Override
    public void delete(Long id) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        Long childCount = categoryMapper.selectCount(
                Wrappers.<ProductCategory>lambdaQuery().eq(ProductCategory::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_HAS_CHILDREN);
        }
        Long productCount = productMapper.selectCount(
                Wrappers.<Product>lambdaQuery().eq(Product::getCategoryId, id));
        if (productCount != null && productCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_HAS_PRODUCTS);
        }
        categoryMapper.deleteById(id);
    }

    @Override
    public CategoryVO getById(Long id) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        return toVO(category);
    }

    @Override
    public List<CategoryNodeVO> tree() {
        List<CategoryNodeVO> nodes = categoryMapper.selectList(
                        Wrappers.<ProductCategory>lambdaQuery().orderByAsc(ProductCategory::getSort))
                .stream().map(this::toNode).collect(Collectors.toList());

        Map<Long, List<CategoryNodeVO>> childrenMap = nodes.stream()
                .filter(node -> node.getParentId() != null && node.getParentId() != 0)
                .collect(Collectors.groupingBy(CategoryNodeVO::getParentId));

        return nodes.stream()
                .filter(node -> node.getParentId() == null || node.getParentId() == 0)
                .peek(node -> attach(node, childrenMap))
                .sorted(Comparator.comparing(CategoryNodeVO::getSort, Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList());
    }

    private void apply(ProductCategory category, CategorySaveRequest request) {
        if (request.getParentId() != null) {
            category.setParentId(request.getParentId());
        }
        if (request.getName() != null) {
            category.setName(request.getName());
        }
        if (request.getSort() != null) {
            category.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            category.setStatus(request.getStatus());
        }
    }

    private boolean isDescendant(Long selfId, Long candidateParentId) {
        if (candidateParentId == null || candidateParentId == 0) {
            return false;
        }
        ProductCategory current = categoryMapper.selectById(candidateParentId);
        while (current != null && current.getParentId() != 0) {
            if (current.getParentId().equals(selfId)) {
                return true;
            }
            current = categoryMapper.selectById(current.getParentId());
        }
        return false;
    }

    private void attach(CategoryNodeVO parent, Map<Long, List<CategoryNodeVO>> childrenMap) {
        List<CategoryNodeVO> children = new java.util.ArrayList<>(childrenMap.getOrDefault(parent.getId(), List.of()));
        children.sort(Comparator.comparing(CategoryNodeVO::getSort, Comparator.nullsLast(Integer::compareTo)));
        parent.setChildren(children);
        children.forEach(c -> attach(c, childrenMap));
    }

    private CategoryNodeVO toNode(ProductCategory c) {
        CategoryNodeVO node = new CategoryNodeVO();
        node.setId(c.getId());
        node.setParentId(c.getParentId());
        node.setName(c.getName());
        node.setSort(c.getSort());
        node.setStatus(c.getStatus());
        return node;
    }

    private CategoryVO toVO(ProductCategory c) {
        CategoryVO vo = new CategoryVO();
        vo.setId(c.getId());
        vo.setParentId(c.getParentId());
        vo.setName(c.getName());
        vo.setSort(c.getSort());
        vo.setStatus(c.getStatus());
        return vo;
    }
}
