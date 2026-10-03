package com.springshop.admin.controller;

import com.springshop.admin.aspect.OperationLog;
import com.springshop.admin.dto.AdminPasswordResetRequest;
import com.springshop.admin.dto.AdminRoleAssignRequest;
import com.springshop.admin.dto.AdminUserCreateRequest;
import com.springshop.admin.dto.AdminUserPageQuery;
import com.springshop.admin.dto.AdminUserUpdateRequest;
import com.springshop.admin.service.AdminUserImportService;
import com.springshop.admin.service.AdminUserService;
import com.springshop.admin.vo.AdminUserImportResultVO;
import com.springshop.admin.vo.AdminUserVO;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 管理员账号管理接口
 *
 * <p>不提供删除接口：{@code admin_user} 为逻辑删除且用户名唯一，软删后用户名不释放，
 * 同名账号无法重建（重启时初始化器补建 admin 还会撞唯一键）。收回权限请使用「禁用」。
 */
@Tag(name = "后台管理员管理", description = "管理员账号 CRUD、启停、重置密码、分配角色与批量导入")
@RestController
@RequestMapping("/api/admin/users")
@Validated
public class AdminUserController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AdminUserService adminUserService;
    private final AdminUserImportService adminUserImportService;

    public AdminUserController(AdminUserService adminUserService,
                              AdminUserImportService adminUserImportService) {
        this.adminUserService = adminUserService;
        this.adminUserImportService = adminUserImportService;
    }

    @Operation(summary = "分页查询管理员")
    @PreAuthorize("hasAuthority('system:user:list')")
    @GetMapping
    public Result<PageResult<AdminUserVO>> page(@Valid AdminUserPageQuery query) {
        return Result.success(adminUserService.page(query));
    }

    @Operation(summary = "查询管理员详情")
    @PreAuthorize("hasAuthority('system:user:list')")
    @GetMapping("/{id}")
    public Result<AdminUserVO> detail(@PathVariable Long id) {
        return Result.success(adminUserService.detail(id));
    }

    @Operation(summary = "新增管理员")
    @OperationLog(module = "系统管理", operation = "新增管理员")
    @PreAuthorize("hasAuthority('system:user:create')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AdminUserCreateRequest request) {
        return Result.success(adminUserService.create(request));
    }

    @Operation(summary = "修改管理员基本信息")
    @OperationLog(module = "系统管理", operation = "修改管理员")
    @PreAuthorize("hasAuthority('system:user:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AdminUserUpdateRequest request) {
        adminUserService.update(id, request, UserContext.getUserId());
        return Result.success();
    }

    @Operation(summary = "启用 / 禁用管理员", description = "不允许禁用当前登录账号")
    @OperationLog(module = "系统管理", operation = "启停管理员")
    @PreAuthorize("hasAuthority('system:user:update')")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminUserService.updateStatus(id, status, UserContext.getUserId());
        return Result.success();
    }

    @Operation(summary = "重置管理员密码")
    @OperationLog(module = "系统管理", operation = "重置管理员密码")
    @PreAuthorize("hasAuthority('system:user:reset')")
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody AdminPasswordResetRequest request) {
        adminUserService.resetPassword(id, request.getNewPassword());
        return Result.success();
    }

    @Operation(summary = "给管理员分配角色")
    @OperationLog(module = "系统管理", operation = "分配管理员角色")
    @PreAuthorize("hasAuthority('system:user:assign')")
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AdminRoleAssignRequest request) {
        adminUserService.assignRoles(id, request.getRoleIds());
        return Result.success();
    }

    @Operation(summary = "批量导入管理员", description = "部分成功：合法行写入，非法行在返回结果中给出原因")
    @OperationLog(module = "系统管理", operation = "导入管理员")
    @PreAuthorize("hasAuthority('system:user:import')")
    @PostMapping("/import")
    public Result<AdminUserImportResultVO> importUsers(@RequestPart("file") MultipartFile file) {
        return Result.success(adminUserImportService.importUsers(file));
    }

    @Operation(summary = "下载管理员导入模板")
    @PreAuthorize("hasAuthority('system:user:import')")
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        // 文件下载无法套 Result<T> 包装，直接返回二进制流（业务失败仍走全局异常处理）
        byte[] content = adminUserImportService.buildTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("管理员导入模板.xlsx", StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }
}
