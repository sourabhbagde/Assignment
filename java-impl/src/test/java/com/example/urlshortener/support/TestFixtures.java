package com.example.urlshortener.support;

import com.example.urlshortener.application.config.AppProperties;

/** Shared builders for unit tests that need a populated {@link AppProperties}. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static AppProperties props() {
        return new AppProperties(
                "http://localhost:3000",
                7,
                6,
                "302",
                true,
                2048,
                true,
                false,
                "test-salt",
                4096,
                false,
                new AppProperties.RateLimit(20, 2, 100, 50),
                new AppProperties.Analytics(1000, 200, 10_000, 512, 512),
                new AppProperties.Cache(1000, 60_000));
    }
}
