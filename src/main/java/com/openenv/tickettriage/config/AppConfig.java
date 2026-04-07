package com.openenv.tickettriage.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs()
                        .maxInMemorySize(5 * 1024 * 1024)) // 5 MB
                .build();
        return WebClient.builder().exchangeStrategies(strategies);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    @Bean
    public OpenAPI openApiSpec() {
        return new OpenAPI().info(new Info()
                .title("OpenEnv — IT Support Ticket Triage")
                .version("1.0.0")
                .description("""
                    A production-grade RL environment for training AI agents to triage IT support tickets.
                    
                    **Tasks**
                    - `EASY`   — Priority classification (1 step)
                    - `MEDIUM` — Priority + Category + Team assignment (1 step)
                    - `HARD`   — Full triage with resolution suggestion (up to 3 steps)
                    
                    **Quick Start**
                    1. `POST /api/v1/reset` with `{"task_type": "EASY"}`
                    2. `POST /api/v1/step`  with your action
                    3. `GET  /api/v1/state` to inspect ground truth
                    """)
                .contact(new Contact().name("OpenEnv Community").url("https://huggingface.co"))
                .license(new License().name("MIT")));
    }
}
