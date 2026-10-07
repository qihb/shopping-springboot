package com.springshop.order.controller;

import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.order.dto.AddressSaveRequest;
import com.springshop.order.service.AddressService;
import com.springshop.order.vo.AddressVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 收货地址接口（均需登录，用户 id 取自 {@link UserContext}）
 */
@Tag(name = "收货地址", description = "前台收货地址相关接口")
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @Operation(summary = "我的地址列表（默认地址在前）")
    @ApiResponse(responseCode = "200", description = "返回我的收货地址列表，默认地址在前")
    @GetMapping
    public Result<List<AddressVO>> list() {
        return Result.success(addressService.list(UserContext.getUserId()));
    }

    @Operation(summary = "新增地址")
    @ApiResponse(responseCode = "200", description = "返回新增收货地址的 id")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AddressSaveRequest request) {
        return Result.success(addressService.create(UserContext.getUserId(), request));
    }

    @Operation(summary = "修改地址")
    @ApiResponse(responseCode = "200", description = "修改成功，无返回数据")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AddressSaveRequest request) {
        addressService.update(UserContext.getUserId(), id, request);
        return Result.success();
    }

    @Operation(summary = "设为默认地址")
    @ApiResponse(responseCode = "200", description = "设置成功，无返回数据")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(UserContext.getUserId(), id);
        return Result.success();
    }

    @Operation(summary = "删除地址")
    @ApiResponse(responseCode = "200", description = "删除成功，无返回数据")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(UserContext.getUserId(), id);
        return Result.success();
    }
}
