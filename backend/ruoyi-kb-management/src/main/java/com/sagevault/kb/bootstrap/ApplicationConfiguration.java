package com.sagevault.kb.bootstrap;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ApplicationConfiguration {
    @Bean
    public WebClient.Builder webClient() { return WebClient.builder(); }
}
