package com.springshop.user.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.user.entity.User;
import com.springshop.user.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security 用户加载服务：按用户名查库并适配为 UserDetails
 *
 * <p>Spring Security 的认证流程会自动发现此 Bean（classpath 中唯一
 * 的 UserDetailsService 实现）并在每次请求认证时调用。
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    public UserDetailsServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return new UserPrincipal(user);
    }
}
