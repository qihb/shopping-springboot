package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.product.dto.BrandSaveRequest;
import com.springshop.product.product.entity.Brand;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.mapper.BrandMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.service.BrandService;
import com.springshop.product.product.vo.BrandVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 品牌服务实现
 *
 * <p>品牌是基础资料，没有缓存；同名判断只看未逻辑删除的记录（与 {@code uk_brand_name} 索引口径一致，
 * 但索引在逻辑删除下会残留占用，因此应用层再兜一道）。
 */
@Service
public class BrandServiceImpl implements BrandService {

    private final BrandMapper brandMapper;
    private final ProductMapper productMapper;

    public BrandServiceImpl(BrandMapper brandMapper, ProductMapper productMapper) {
        this.brandMapper = brandMapper;
        this.productMapper = productMapper;
    }

    @Override
    public List<BrandVO> list() {
        return brandMapper.selectList(Wrappers.<Brand>lambdaQuery()
                        .orderByAsc(Brand::getSort)
                        .orderByAsc(Brand::getId))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public BrandVO getById(Long id) {
        return toVO(require(id));
    }

    @Override
    public void create(BrandSaveRequest request) {
        String name = request.getName().trim();
        ensureNameAvailable(name, null);

        Brand brand = new Brand();
        brand.setName(name);
        brand.setLogo(request.getLogo());
        brand.setSort(request.getSort() == null ? 0 : request.getSort());
        brand.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        brandMapper.insert(brand);
    }

    @Override
    public void update(Long id, BrandSaveRequest request) {
        Brand brand = require(id);
        String name = request.getName().trim();
        ensureNameAvailable(name, id);

        brand.setName(name);
        brand.setLogo(request.getLogo());
        if (request.getSort() != null) {
            brand.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            brand.setStatus(request.getStatus());
        }
        brandMapper.updateById(brand);
    }

    @Override
    public void delete(Long id) {
        require(id);
        Long productCount = productMapper.selectCount(
                Wrappers.<Product>lambdaQuery().eq(Product::getBrandId, id));
        if (productCount != null && productCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_BRAND_HAS_PRODUCTS);
        }
        brandMapper.deleteById(id);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private Brand require(Long id) {
        Brand brand = brandMapper.selectById(id);
        if (brand == null) {
            throw new BusinessException(ResultCode.PRODUCT_BRAND_NOT_FOUND);
        }
        return brand;
    }

    /**
     * 校验品牌名未被占用
     *
     * @param excludeId 修改时排除自身，新增传 null
     */
    private void ensureNameAvailable(String name, Long excludeId) {
        Long count = brandMapper.selectCount(Wrappers.<Brand>lambdaQuery()
                .eq(Brand::getName, name)
                .ne(excludeId != null, Brand::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.PRODUCT_BRAND_NAME_EXISTS);
        }
    }

    private BrandVO toVO(Brand brand) {
        BrandVO vo = new BrandVO();
        vo.setId(brand.getId());
        vo.setName(brand.getName());
        vo.setLogo(brand.getLogo());
        vo.setSort(brand.getSort());
        vo.setStatus(brand.getStatus());
        return vo;
    }
}
