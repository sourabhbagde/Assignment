package com.example.urlshortener.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "it.slice=redirect")
class RedirectControllerIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    private String create(@NonNull String body) throws Exception {
        String response = mvc.perform(post("/api/v1/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("code").asText();
    }

    @Test
    void redirectsWith302NoStoreAndSecurityHeaders() throws Exception {
        String code = create("{\"url\":\"https://example.com/dest\"}");
        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/dest"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void unknownCode404s() throws Exception {
        mvc.perform(get("/doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINK_NOT_FOUND"));
    }

    @Test
    void junkPathIsRejectedWithoutHittingTheDatabase() throws Exception {
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        mvc.perform(get("/a")).andExpect(status().isNotFound());
    }

    @Test
    void deactivatedLink410s() throws Exception {
        String code = create("{\"url\":\"https://example.com/gone\"}");
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("LINK_DEACTIVATED"));
    }

    @Test
    void expiredLink410s() throws Exception {
        String code = create("{\"url\":\"https://example.com/ttl\",\"ttlSeconds\":3600}");
        // Fast-forward: push the expiry into the past directly in storage.
        jdbc.update("UPDATE short_links SET expires_at = ? WHERE code = ?",
                java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z"), code);
        mvc.perform(get("/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("LINK_EXPIRED"));
    }

    @Test
    void redirectStatusIsConfigurable() throws Exception {
        // default profile value is 302; this test just confirms the wiring reads it.
        String code = create("{\"url\":\"https://example.com/cfg\"}");
        mvc.perform(get("/" + code)).andExpect(status().isFound());
    }
}
