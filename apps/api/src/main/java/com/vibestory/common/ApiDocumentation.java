package com.vibestory.common;

import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiDocumentation {
    @Bean
    OpenApiCustomizer csrfHeader() {
        return api -> api.getPaths().values().forEach(path -> path.readOperationsMap().forEach((method, operation) -> {
            if (method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT ||
                    method == PathItem.HttpMethod.PATCH || method == PathItem.HttpMethod.DELETE) {
                operation.addParametersItem(new Parameter().name("X-CSRF-TOKEN").in("header").required(true)
                    .description("Fetch /api/auth/csrf with the same session cookie; refresh after login.")
                    .schema(new StringSchema()));
            }
        }));
    }
}
