package com.pidea.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI pideaOpenApi() {
        return new OpenAPI()
            .info(new Info().title("PIDEA Integration API").version("v1")
                .description("Platform for Integration, Data Engineering & Analytics. Synthetic-data portfolio project."))
            .components(new Components().addSecuritySchemes("basicAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
