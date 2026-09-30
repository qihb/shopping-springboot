package com.springshop.cart.controller;

import com.springshop.cart.dto.CartAddRequest;
import com.springshop.cart.dto.CartCheckedRequest;
import com.springshop.cart.dto.CartQuantityRequest;
import com.springshop.cart.service.CartService;
import com.springshop.cart.vo.CartVO;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * 购物车接口（均需登录，用户 id 取自 {@link UserContext}）
 */
@Tag(name = "购物车", description = "前台购物车相关接口")
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @Operation(summary = "加入购物车")
    @PostMapping("/items")
    public Result<Void> add(@Valid @RequestBody CartAddRequest request) {
        cartService.add(UserContext.getUserId(), request);
        return Result.success();
    }

    @Operation(summary = "购物车列表（含汇总）")
    @GetMapping
    public Result<CartVO> list() {
        return Result.success(cartService.list(UserContext.getUserId()));
    }

    @Operation(summary = "修改条目数量")
    @PutMapping("/items/{id}")
    public Result<Void> updateQuantity(@PathVariable Long id, @Valid @RequestBody CartQuantityRequest request) {
        cartService.updateQuantity(UserContext.getUserId(), id, request);
        return Result.success();
    }

    @Operation(summary = "单条勾选 / 取消勾选")
    @PutMapping("/items/{id}/checked")
    public Result<Void> updateChecked(@PathVariable Long id, @Valid @RequestBody CartCheckedRequest request) {
        cartService.updateChecked(UserContext.getUserId(), id, request);
        return Result.success();
    }

    @Operation(summary = "全选 / 全不选")
    @PutMapping("/checked")
    public Result<Void> updateAllChecked(@Valid @RequestBody CartCheckedRequest request) {
        cartService.updateAllChecked(UserContext.getUserId(), request);
        return Result.success();
    }

    @Operation(summary = "删除单条")
    @DeleteMapping("/items/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        cartService.delete(UserContext.getUserId(), id);
        return Result.success();
    }

    @Operation(summary = "删除已勾选条目")
    @DeleteMapping("/checked")
    public Result<Void> deleteChecked() {
        cartService.deleteChecked(UserContext.getUserId());
        return Result.success();
    }

    @Operation(summary = "清空购物车")
    @DeleteMapping
    public Result<Void> clear() {
        cartService.clear(UserContext.getUserId());
        return Result.success();
    }
}
