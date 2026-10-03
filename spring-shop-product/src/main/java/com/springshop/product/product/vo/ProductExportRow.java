package com.springshop.product.product.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 商品导出模型
 *
 * <p>用注解模型（{@code @ExcelProperty}）而不是 {@code List<List<String>>}：
 * 列名与顺序跟着字段走，调整列不用同时改表头数组和每个取值下标；
 * 类型也交给 Fesod 的转换器处理，不用手工拼字符串。
 *
 * <p>时间统一格式化成字符串：导出是给人看的，不希望同一列在不同机器上
 * 因为时区/格式配置差异而变样。
 */
public class ProductExportRow {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ExcelProperty("商品ID")
    private Long id;

    @ExcelProperty("商品名称")
    private String name;

    @ExcelProperty("副标题")
    private String subtitle;

    @ExcelProperty("分类")
    private String categoryName;

    @ExcelProperty("最低价(元)")
    private BigDecimal minPrice;

    @ExcelProperty("销量")
    private Integer sales;

    @ExcelProperty("状态")
    private String statusName;

    @ExcelProperty("主图URL")
    private String mainImage;

    @ExcelProperty("创建时间")
    private String createTime;

    public ProductExportRow() {
    }

    /**
     * 从列表 VO 转换（导出直接复用列表页的查询结果，保证「看到的」和「导出的」一致）
     */
    public static ProductExportRow from(ProductListVO vo) {
        ProductExportRow row = new ProductExportRow();
        row.setId(vo.getId());
        row.setName(vo.getName());
        row.setSubtitle(vo.getSubtitle());
        row.setCategoryName(vo.getCategoryName());
        row.setMinPrice(vo.getMinPrice());
        row.setSales(vo.getSales());
        row.setStatusName(statusLabel(vo.getStatus()));
        row.setMainImage(vo.getMainImage());
        row.setCreateTime(formatTime(vo.getCreateTime()));
        return row;
    }

    private static String statusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return status == 1 ? "上架" : "下架";
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FORMAT);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public BigDecimal getMinPrice() { return minPrice; }
    public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }
    public String getStatusName() { return statusName; }
    public void setStatusName(String statusName) { this.statusName = statusName; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
