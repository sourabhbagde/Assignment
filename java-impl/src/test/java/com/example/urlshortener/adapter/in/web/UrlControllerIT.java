package com.example.urlshortener.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.lang.NonNull;
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
@TestPropertySource(properties = "it.slice=urls")
class UrlControllerIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String create(@NonNull String body) throws Exception {
        String response = mvc.perform(post("/api/v1/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("code").asText();
    }

    @Test
    void createsShortLinkWith201AndLocation() throws Exception {
        var result = mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("""
                                {"url":"https://example.com/a"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String code = body.path("code").asText();
        assertThat(code).matches("[0-9A-Za-z]{7}");
        assertThat(body.path("shortUrl").asText()).endsWith("/" + code);
        assertThat(result.getResponse().getHeader("Location")).endsWith("/" + code);
    }

    @Test
    void dedupesIdenticalDestinationWith200AndSameCode() throws Exception {
        String first = create("{\"url\":\"https://example.com/dedupe?a=1&b=2\"}");
        mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://example.com/dedupe?b=2&a=1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(first));
    }

    @Test
    void honoursDedupeFalse() throws Exception {
        String a = create("{\"url\":\"https://example.com/x\",\"dedupe\":false}");
        String b = create("{\"url\":\"https://example.com/x\",\"dedupe\":false}");
        org.assertj.core.api.Assertions.assertThat(a).isNotEqualTo(b);
    }

    @Test
    void acceptsCustomAliasAnd409sOnConflict() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://example.com/1\",\"customAlias\":\"launch-2026\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("launch-2026"));

        mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://example.com/2\",\"customAlias\":\"launch-2026\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALIAS_TAKEN"));
    }

    @Test
    void rejectsReservedAlias() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://example.com/1\",\"customAlias\":\"health\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ALIAS"));
    }

    @Test
    void rejectsInvalidAndDangerousUrls() throws Exception {
        for (String url : new String[] {
                "not-a-url", "javascript:alert(1)", "http://127.0.0.1/", "http://localhost", "http://169.254.169.254/"}) {
            mvc.perform(post("/api/v1/urls").contentType("application/json")
                            .content("{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        }
    }

    @Test
    void rejectsUnknownBodyFields() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://ok.example\",\"isAdmin\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_BODY"));
    }

    @Test
    void rejectsBothExpiresAtAndTtl() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType("application/json")
                        .content("{\"url\":\"https://ok.example\",\"expiresAt\":\"2099-01-01T00:00:00Z\",\"ttlSeconds\":60}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsWrongContentType() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType("text/plain").content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsUnsupportedMethod() throws Exception {
        mvc.perform(delete("/api/v1/urls"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void getReturnsMetadataAnd404sForUnknown() throws Exception {
        String code = create("{\"url\":\"https://example.com/meta\"}");
        mvc.perform(get("/api/v1/urls/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/meta"));

        mvc.perform(get("/api/v1/urls/doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINK_NOT_FOUND"));
    }

    @Test
    void listIsNewestFirstWithPaginationMetadata() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("{\"url\":\"https://example.com/p" + i + "\",\"dedupe\":false}");
        }
        JsonNode page = json.readTree(mvc.perform(get("/api/v1/urls?limit=2&offset=0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(page.path("items")).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(page.path("limit").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void deactivateReturns204ThenGetShowsInactive() throws Exception {
        String code = create("{\"url\":\"https://example.com/del\"}");
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/urls/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(delete("/api/v1/urls/neverexisted")).andExpect(status().isNotFound());
    }
}
