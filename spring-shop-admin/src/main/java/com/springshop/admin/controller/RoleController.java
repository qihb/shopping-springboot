package com.springshop.admin.controller;

import com.springshop.admin.aspect.OperationLog;
import com.springshop.admin.dto.RoleAssignMenuRequest;
import com.springshop.admin.dto.RoleSaveRequest;
import com.springshop.admin.service.RoleService;
import com.springshop.admin.vo.RoleVO;
import com.springshop.common.dto.PageQuery;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
 * 角色管理接口
 */
@Tag(name = "后台角色管理", description = "角色 CRUD 与菜单权限分配")
@RestController
@RequestMapping("/api/admin/roles")
@Validated
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "分页查询角色")
    @PreAuthorize("hasAuthority('system:role:list')")
    @GetMapping
    public Result<PageResult<RoleVO>> page(@Valid PageQuery pageQuery,
                                           @RequestParam(required = false) String name) {
        return Result.success(roleService.page(pageQuery, name));
    }

    @Operation(summary = "查询全部启用角色")
    @PreAuthorize("hasAuthority('system:role:list')")
    @GetMapping("/all")
    public Result<List<RoleVO>> listAll() {
        return Result.success(roleService.listAll());
    }

    @Operation(summary = "新增角色")
    @OperationLog(module = "系统管理", operation = "新增角色")
    @PreAuthorize("hasAuthority('system:role:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody RoleSaveRequest request) {
        roleService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改角色")
    @OperationLog(module = "系统管理", operation = "修改角色")
    @PreAuthorize("hasAuthority('system:role:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveRequest request) {
        roleService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "删除角色", description = "已被管理员绑定的角色不可删除")
    @OperationLog(module = "系统管理", operation = "删除角色")
    @PreAuthorize("hasAuthority('system:role:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success();
    }

    @Operation(summary = "给角色分配菜单权限")
    @OperationLog(module = "系统管理", operation = "分配角色权限")
    @PreAuthorize("hasAuthority('system:role:assign')")
    @PostMapping("/{id}/menus")
    public Result<Void> assignMenus(@PathVariable Long id, @Valid @RequestBody RoleAssignMenuRequest request) {
        roleService.assignMenus(id, request.getMenuIds());
        return Result.success();
    }

    @Operation(summary = "查询角色已分配的菜单 id")
    @PreAuthorize("hasAuthority('system:role:list')")
    @GetMapping("/{id}/menus")
    public Result<List<Long>> listMenuIds(@PathVariable Long id) {
        return Result.success(roleService.listMenuIds(id));
    }
}
