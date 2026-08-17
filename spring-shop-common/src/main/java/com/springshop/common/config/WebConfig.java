package com.springshop.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 全局配置（当前用于跨域 CORS）
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
}
