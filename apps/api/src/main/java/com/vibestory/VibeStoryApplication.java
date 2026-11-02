package com.vibestory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@OpenAPIDefinition(info = @Info(title = "VibeStory API", version = "0.7.0",
    description = "Scoped creation workspace: projects, stories, breakdown, versioned assets, image tasks, MiniMax video with persisted external IDs and first-frame provenance. Writes require a CSRF token from /api/auth/csrf."))
@SecurityScheme(name = "session", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.COOKIE, paramName = "JSESSIONID")
public class VibeStoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(VibeStoryApplication.class, args);
    }
}
