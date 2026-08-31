package com.springshop.admin.security;

import com.springshop.admin.entity.AdminUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员认证主体：把业务管理员 {@link AdminUser} 适配为 {@link UserDetails}
 *
 * <p>authorities 即权限标识集合（如 {@code product:sku:edit}），
 * 供方法级 {@code @PreAuthorize("hasAuthority('...')")} 做细粒度授权。
 */
public class AdminUserPrincipal implements UserDetails {

    private final Long id;

    private final String username;

    private final String password;

    private final boolean enabled;

    /** 权限标识集合 */
    private final Set<String> permissions;

    public AdminUserPrincipal(AdminUser adminUser, Set<String> permissions) {
        this.id = adminUser.getId();
        this.username = adminUser.getUsername();
        this.password = adminUser.getPassword();
        this.enabled = adminUser.getStatus() == 1;
        this.permissions = permissions;
    }

    /**
     * 管理员 id，供过滤器写入 {@code UserContext}
     */
    public Long getId() {
        return id;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
