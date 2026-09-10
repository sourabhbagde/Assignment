package com.example.urlshortener;

import com.example.urlshortener.application.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Application entrypoint. Graceful shutdown (drain in-flight requests, flush
 * buffered analytics via {@code @PreDestroy}, close the pool) is enabled in
 * {@code application.yml} ({@code server.shutdown=graceful}).
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }

    /** A single injectable clock so time-dependent logic is testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
