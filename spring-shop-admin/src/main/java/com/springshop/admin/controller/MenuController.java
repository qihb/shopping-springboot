package com.springshop.admin.controller;

import com.springshop.admin.aspect.OperationLog;
import com.springshop.admin.dto.MenuSaveRequest;
import com.springshop.admin.service.MenuService;
import com.springshop.admin.vo.MenuNodeVO;
import com.springshop.common.result.Result;
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
 * 菜单 / 权限管理接口
 */
@Tag(name = "后台菜单管理", description = "菜单树 CRUD")
@RestController
@RequestMapping("/api/admin/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @Operation(summary = "查询菜单树")
    @PreAuthorize("hasAuthority('system:menu:list')")
    @GetMapping("/tree")
    public Result<List<MenuNodeVO>> tree() {
        return Result.success(menuService.tree());
    }

    @Operation(summary = "新增菜单")
    @OperationLog(module = "系统管理", operation = "新增菜单")
    @PreAuthorize("hasAuthority('system:menu:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody MenuSaveRequest request) {
        menuService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改菜单")
    @OperationLog(module = "系统管理", operation = "修改菜单")
    @PreAuthorize("hasAuthority('system:menu:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MenuSaveRequest request) {
        menuService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "删除菜单", description = "存在子节点时不可删除")
    @OperationLog(module = "系统管理", operation = "删除菜单")
    @PreAuthorize("hasAuthority('system:menu:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.success();
    }
}
