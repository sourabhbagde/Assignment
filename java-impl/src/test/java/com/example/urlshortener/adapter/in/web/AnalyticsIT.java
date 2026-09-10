package com.example.urlshortener.adapter.in.web;

import com.example.urlshortener.application.service.BufferedClickRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "it.slice=analytics")
class AnalyticsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BufferedClickRecorder recorder;

    private String create(String url) throws Exception {
        String response = mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"" + url + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("code").asText();
    }

    private void hit(String code, String referer, String ua) throws Exception {
        var req = get("/" + code);
        if (referer != null) {
            req = req.header("Referer", referer);
        }
        if (ua != null) {
            req = req.header("User-Agent", ua);
        }
        mvc.perform(req).andExpect(status().isFound());
    }

    @Test
    void aggregatesClicksReferrersAndUserAgents() throws Exception {
        String code = create("https://example.com/track");
        hit(code, "https://news.ycombinator.com", "UA-1");
        hit(code, "https://news.ycombinator.com", "UA-1");
        hit(code, null, "UA-2");
        recorder.flush();

        JsonNode stats = json.readTree(mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(stats.path("totalClicks").asLong()).isEqualTo(3);
        long dailySum = 0;
        for (JsonNode d : stats.path("daily")) {
            dailySum += d.path("count").asLong();
        }
        assertThat(dailySum).isEqualTo(3);
        assertThat(stats.path("lastClickedAt").asText()).isNotBlank();

        JsonNode referrers = stats.path("topReferrers");
        assertThat(nodeCount(referrers, "https://news.ycombinator.com")).isEqualTo(2);
        assertThat(nodeCount(referrers, "(direct)")).isEqualTo(1);
    }

    @Test
    void storesOnlyASaltedHashOfTheClientIp() throws Exception {
        String code = create("https://example.com/priv");
        hit(code, null, "curl");
        recorder.flush();

        String ipHash = jdbc.queryForObject(
                "SELECT ip_hash FROM click_events WHERE code = ? LIMIT 1", String.class, code);
        assertThat(ipHash).matches("[0-9a-f]{64}");
        // The raw loopback address must not be recoverable from the column.
        assertThat(ipHash).doesNotContain("127.0.0.1").doesNotContain("0:0:0:0");
    }

    @Test
    void doesNotRecordClicksForDeactivatedLinks() throws Exception {
        String code = create("https://example.com/x");
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isGone());
        recorder.flush();

        mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(jsonPath("$.totalClicks").value(0));
    }

    @Test
    void filtersByWindowWhileTotalIgnoresIt() throws Exception {
        String code = create("https://example.com/win");
        hit(code, null, "ua");
        recorder.flush();

        String future = java.time.Instant.now().plusSeconds(86_400).toString();
        mvc.perform(get("/api/v1/urls/" + code + "/stats").param("from", future))
                .andExpect(jsonPath("$.windowClicks").value(0))
                .andExpect(jsonPath("$.totalClicks").value(1));
    }

    @Test
    void rejectsInvertedWindow() throws Exception {
        String code = create("https://example.com/v");
        mvc.perform(get("/api/v1/urls/" + code + "/stats")
                        .param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private static long nodeCount(JsonNode breakdown, String value) {
        for (JsonNode n : breakdown) {
            if (value.equals(n.path("value").asText())) {
                return n.path("count").asLong();
            }
        }
        return 0;
    }
}
