package dev.ledger.engine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata + the X-Api-Key scheme so Swagger UI's "Authorize" works. */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKey";

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledger Engine API")
                        .version("0.1.0")
                        .description("Double-entry payment ledger. Every endpoint except "
                                + "/health requires the X-Api-Key header."))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")));
    }
}
