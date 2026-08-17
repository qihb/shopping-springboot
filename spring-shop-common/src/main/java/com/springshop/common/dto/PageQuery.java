package com.springshop.common.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 分页查询入参基类
 *
 * <p>所有列表查询接口继承此类，统一接收分页参数并提供默认值，
 * 避免每个 DTO 重复声明 current/size。
 */
public class PageQuery {

    /** 当前页码，从 1 开始 */
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于 1")
    private Long current = 1L;

    /** 每页大小 */
    @NotNull(message = "每页大小不能为空")
    @Min(value = 1, message = "每页大小必须大于等于 1")
    @Max(value = 100, message = "每页大小不能超过 100")
    private Long size = 10L;

    /**
     * 转换为 MyBatis-Plus 分页对象
     */
    public <T> Page<T> toPage() {
        return new Page<>(current, size);
    }

    public Long getCurrent() {
        return current;
    }

    public void setCurrent(Long current) {
        this.current = current;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }
}
