package com.springshop.product.controller.admin;

import com.springshop.common.result.Result;
import com.springshop.product.product.dto.AttributeSaveRequest;
import com.springshop.product.product.dto.AttributeValueSaveRequest;
import com.springshop.product.product.service.AttributeService;
import com.springshop.product.product.vo.AttributeVO;
import com.springshop.product.product.vo.AttributeValueVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台商品属性（规格体系）管理接口
 *
 * <p>可选值的增删改挂在属性下：{@code /api/admin/attributes/{attributeId}/values}，
 * 权限复用属性的 {@code product:attribute:update} —— 维护可选值本身就是「修改属性」的一部分。
 */
@Tag(name = "后台商品属性管理")
@RestController
@RequestMapping("/api/admin/attributes")
public class AdminAttributeController {

    private final AttributeService attributeService;

    public AdminAttributeController(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @Operation(summary = "按分类查询属性（含可选值）")
    @ApiResponse(responseCode = "200", description = "返回属性列表")
    @PreAuthorize("hasAuthority('product:attribute:list')")
    @GetMapping
    public Result<List<AttributeVO>> listByCategory(@RequestParam Long categoryId) {
        return Result.success(attributeService.listByCategory(categoryId));
    }

    @Operation(summary = "属性详情（含可选值）")
    @ApiResponse(responseCode = "200", description = "返回属性详情")
    @PreAuthorize("hasAuthority('product:attribute:list')")
    @GetMapping("/{id}")
    public Result<AttributeVO> getById(@PathVariable Long id) {
        return Result.success(attributeService.getById(id));
    }

    @Operation(summary = "新增属性")
    @ApiResponse(responseCode = "200", description = "新增属性成功，无返回数据")
    @PreAuthorize("hasAuthority('product:attribute:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody AttributeSaveRequest request) {
        attributeService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改属性")
    @ApiResponse(responseCode = "200", description = "修改属性成功，无返回数据")
    @PreAuthorize("hasAuthority('product:attribute:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AttributeSaveRequest request) {
        attributeService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "删除属性")
    @ApiResponse(responseCode = "200", description = "删除属性成功，无返回数据")
    @PreAuthorize("hasAuthority('product:attribute:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        attributeService.delete(id);
        return Result.success();
    }

    @Operation(summary = "新增属性可选值")
    @ApiResponse(responseCode = "200", description = "新增可选值成功，返回新增的可选值")
    @PreAuthorize("hasAuthority('product:attribute:update')")
    @PostMapping("/{attributeId}/values")
    public Result<AttributeValueVO> addValue(@PathVariable Long attributeId,
                                             @Valid @RequestBody AttributeValueSaveRequest request) {
        return Result.success(attributeService.addValue(attributeId, request));
    }

    @Operation(summary = "修改属性可选值")
    @ApiResponse(responseCode = "200", description = "修改可选值成功，无返回数据")
    @PreAuthorize("hasAuthority('product:attribute:update')")
    @PutMapping("/{attributeId}/values/{valueId}")
    public Result<Void> updateValue(@PathVariable Long attributeId,
                                    @PathVariable Long valueId,
                                    @Valid @RequestBody AttributeValueSaveRequest request) {
        attributeService.updateValue(attributeId, valueId, request);
        return Result.success();
    }

    @Operation(summary = "删除属性可选值")
    @ApiResponse(responseCode = "200", description = "删除可选值成功，无返回数据")
    @PreAuthorize("hasAuthority('product:attribute:update')")
    @DeleteMapping("/{attributeId}/values/{valueId}")
    public Result<Void> deleteValue(@PathVariable Long attributeId, @PathVariable Long valueId) {
        attributeService.deleteValue(attributeId, valueId);
        return Result.success();
    }
}
