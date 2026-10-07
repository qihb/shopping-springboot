package com.springshop.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
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

    /**
     * 全局响应说明：给所有接口统一补上通用失败响应的中文描述。
     *
     * <p>springdoc 默认只给出英文的 {@code OK} / {@code Forbidden}，且每个接口都自带 401/403/500，
     * 若逐个 Controller 标注会产生大量重复。这里在文档生成后统一改写，
     * 各接口只需用 {@code @ApiResponse} 描述自己的成功响应。
     */
    @Bean
    public OpenApiCustomizer globalFailureResponseCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    operation.setResponses(responses);
                }
                setResponseDescription(responses, "401", "未登录或登录已失效，请重新登录");
                setResponseDescription(responses, "403", "无权限访问该接口");
                setResponseDescription(responses, "500", "系统内部错误");
            }));
        };
    }

    /** 设置响应描述：已有该响应码则改写描述，没有则新增 */
    private void setResponseDescription(ApiResponses responses, String code, String description) {
        ApiResponse response = responses.get(code);
        if (response == null) {
            responses.addApiResponse(code, new ApiResponse().description(description));
        } else {
            response.setDescription(description);
        }
    }
}
