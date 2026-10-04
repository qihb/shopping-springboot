package com.springshop.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置：注册 Bearer JWT 认证方案，
 * 使 Swagger UI 右上角出现「Authorize」按钮，调试接口时统一携带 token。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 全局安全方案：HTTP Bearer（JWT）。
     * 仅是文档层声明，不影响运行时鉴权逻辑；
     * 使用时在 Authorize 中粘贴 token 本体即可（无需带 Bearer 前缀）。
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("spring-shop API")
                        .description("spring-shop 电商后端接口文档")
                        .version("v1"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
