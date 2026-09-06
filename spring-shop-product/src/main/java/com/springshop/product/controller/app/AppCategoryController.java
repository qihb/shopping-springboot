package com.springshop.product.controller.app;

import com.springshop.common.result.Result;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台商品分类接口
 */
@Tag(name = "前台商品分类")
@RestController
@RequestMapping("/api/categories")
public class AppCategoryController {

    private final CategoryService categoryService;

    public AppCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "获取启用分类树")
    @GetMapping("/tree")
    public Result<List<CategoryNodeVO>> tree() {
        return Result.success(categoryService.tree());
    }
}
