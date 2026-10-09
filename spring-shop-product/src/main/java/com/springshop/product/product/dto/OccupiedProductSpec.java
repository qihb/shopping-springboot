package com.springshop.product.product.dto;

/**
 * 「已占用的 (商品名称, 规格)」查询投影
 *
 * <p>唯一性判定的单元是 {@code (商品名称, 规格)}，而这两列分属 {@code product} 与
 * {@code product_sku} 两张表，所以查重必须一次 join 查回「名称 → 已占用的规格」，
 * 而不是按名称逐条去查 SKU（一万个商品就是一万次往返）。
 *
 * <p>携带 {@code productId} 是为了让调用方能区分「谁占用的」：
 * <ul>
 *   <li>修改商品时要<b>排除本商品自己</b>的 SKU；</li>
 *   <li>导入时若某个名称已存在，要<b>复用</b>它的 {@code productId} 只追加 SKU，而不是新建 SPU。</li>
 * </ul>
 *
 * <p>这是 Mapper 的查询投影，不是对外 VO：只在本模块的查重逻辑内部流转。
 */
public class OccupiedProductSpec {

    private Long productId;

    private String name;

    /** 规格<b>原文</b>（未规范化）；比较前必须过一遍 {@code SkuSpecNormalizer} */
    private String specs;

    public OccupiedProductSpec() {
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSpecs() {
        return specs;
    }

    public void setSpecs(String specs) {
        this.specs = specs;
    }
}
