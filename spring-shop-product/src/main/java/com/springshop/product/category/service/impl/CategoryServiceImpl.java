package com.springshop.product.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.mapper.ProductMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分类服务实现
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    /** 分类树缓存 TTL */
    private static final Duration TREE_CACHE_TTL = Duration.ofHours(1);

    private final ProductCategoryMapper categoryMapper;

    private final ProductMapper productMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    public CategoryServiceImpl(ProductCategoryMapper categoryMapper,
                               ProductMapper productMapper,
                               StringRedisTemplate stringRedisTemplate,
                               ObjectMapper objectMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
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

        // 分类变更后主动失效树缓存
        stringRedisTemplate.delete(RedisKeys.categoryTree());
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

        // 分类变更后主动失效树缓存
        stringRedisTemplate.delete(RedisKeys.categoryTree());
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

        // 分类变更后主动失效树缓存
        stringRedisTemplate.delete(RedisKeys.categoryTree());
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
        String cached = stringRedisTemplate.opsForValue().get(RedisKeys.categoryTree());
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<CategoryNodeVO>>() {
                });
            } catch (JsonProcessingException e) {
                // 缓存内容损坏视为未命中，走库重建
            }
        }

        List<CategoryNodeVO> tree = buildTree();
        try {
            stringRedisTemplate.opsForValue().set(RedisKeys.categoryTree(),
                    objectMapper.writeValueAsString(tree), TREE_CACHE_TTL);
        } catch (JsonProcessingException e) {
            // 序列化失败只影响缓存写入，不影响本次响应
        }
        return tree;
    }

    /**
     * 从数据库组装分类树（原 tree() 逻辑）
     */
    private List<CategoryNodeVO> buildTree() {
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
