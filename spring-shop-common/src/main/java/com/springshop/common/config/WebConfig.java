package com.springshop.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 全局配置（跨域 CORS + 分页参数名守卫）
 *
 * <p>前后端分离场景下，前端（如 Vite 默认 5173 端口）与后端（8080）端口不同，
 * 浏览器同源策略会拦截响应，需要后端声明允许哪些来源跨域访问。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // allowCredentials(true) 时须用 allowedOriginPatterns 而非 allowedOrigins
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 分页参数名写错（pageNo/pageSize 等）原先会被静默忽略、照常返回第一页，
        // 这里改成明确报错。无状态，直接 new，不需要注册成 bean
        registry.addInterceptor(new PageParamGuardInterceptor());
    }
}
