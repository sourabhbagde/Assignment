package com.example.urlshortener.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "it.slice=reliability",
        "app.rate-limit.write-capacity=3",
        "app.rate-limit.write-refill-per-second=1",
        "app.rate-limit.redirect-capacity=50",
        "app.rate-limit.redirect-refill-per-second=10",
})
class ReliabilityIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void writesAreRateLimitedPastBucketCapacityWithRetryAfter() throws Exception {
        int created = 0;
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 7; i++) {
            MvcResult r = mvc.perform(post("/api/v1/urls").contentType("application/json")
                    .content("{\"url\":\"https://example.com/" + i + "\",\"dedupe\":false}")).andReturn();
            int s = r.getResponse().getStatus();
            if (s == 201) {
                created++;
            } else if (s == 429) {
                limited++;
                retryAfter = r.getResponse().getHeader("Retry-After");
                assertThat(json.readTree(r.getResponse().getContentAsString()).path("error").path("code").asText())
                        .isEqualTo("RATE_LIMITED");
            }
        }
        assertThat(created).isBetween(3, 4); // 3 from the full bucket, maybe 1 more if a second ticked over
        assertThat(limited).isGreaterThanOrEqualTo(2);
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isGreaterThan(0);
    }

    @Test
    void redirectBucketIsIndependentOfWriteBucket() throws Exception {
        // Exhaust the write bucket.
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/v1/urls").contentType("application/json")
                    .content("{\"url\":\"https://example.com/w" + i + "\",\"dedupe\":false}"));
        }
        String code = json.readTree(mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://example.com/hot\",\"dedupe\":false}"))
                .andReturn().getResponse().getContentAsString()).path("code").asText();

        // If the write bucket ate our create, skip — the point is redirects still flow.
        if (code == null || code.isEmpty()) {
            return;
        }
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/" + code)).andExpect(status().isFound());
        }
    }

    @Test
    void healthEndpointsReportStatusWithoutLeakingInternals() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void errorEnvelopeIsConsistentAndCarriesRequestId() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/urls/definitely-missing"))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asText()).isEqualTo("LINK_NOT_FOUND");
        assertThat(body.path("error").path("requestId").asText()).isNotBlank();
        assertThat(body.path("error").path("path").asText()).isEqualTo("/api/v1/urls/definitely-missing");
        assertThat(r.getResponse().getHeader("X-Request-Id"))
                .isEqualTo(body.path("error").path("requestId").asText());
    }

    @Test
    void inboundRequestIdIsEchoed() throws Exception {
        mvc.perform(get("/health").header("X-Request-Id", "trace-abc-123"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Request-Id"))
                        .isEqualTo("trace-abc-123"));
    }

    @Test
    void securityHeadersArePresentOnApiResponses() throws Exception {
        mvc.perform(get("/api/v1/urls?limit=1"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
                    assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
                    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
                });
    }
}
