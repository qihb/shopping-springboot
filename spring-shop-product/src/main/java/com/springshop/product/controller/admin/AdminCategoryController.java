package com.springshop.product.controller.admin;

import com.springshop.common.result.Result;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台商品分类接口
 */
@Tag(name = "后台商品分类管理")
@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "分类树")
    @PreAuthorize("hasAuthority('product:category:list')")
    @GetMapping("/tree")
    public Result<List<CategoryNodeVO>> tree() {
        return Result.success(categoryService.tree());
    }

    @Operation(summary = "分类详情")
    @PreAuthorize("hasAuthority('product:category:list')")
    @GetMapping("/{id}")
    public Result<CategoryVO> getById(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    @Operation(summary = "新增分类")
    @PreAuthorize("hasAuthority('product:category:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody CategorySaveRequest request) {
        categoryService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改分类")
    @PreAuthorize("hasAuthority('product:category:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "删除分类")
    @PreAuthorize("hasAuthority('product:category:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
