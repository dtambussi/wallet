package com.wallet.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Cross-border wallet API")
                .version("1.0")
                .description("""
                    User-scoped resources use `userId` in the path. Optional `Idempotency-Key` header \
                    is scoped per user for mutating operations; if omitted a deterministic fingerprint \
                    of the request body is used as the key (safe for single sends, not for retries).
                    `X-Request-Id` is returned on every response and is stored on ledger entries and the \
                    append-only financial audit log for end-to-end trace (authentication is not modelled in this app).
                    """));
    }
}
