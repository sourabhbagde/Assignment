package com.example.urlshortener.adapter.out.persistence;

import com.example.urlshortener.application.port.out.ShortLinkRepository;
import com.example.urlshortener.domain.model.ShortLink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter for {@link ShortLinkRepository}. All SQL is parameterised via
 * {@link NamedParameterJdbcTemplate} with {@link MapSqlParameterSource} — no
 * string concatenation anywhere, so the SQL-injection surface is nil. Row↔domain
 * mapping is confined to this class.
 */
@Repository
public class JdbcShortLinkRepository implements ShortLinkRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcShortLinkRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final @NonNull RowMapper<ShortLink> rowMapper = this::mapRow;

    public JdbcShortLinkRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public ShortLink insert(ShortLink link) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("code", link.code())
                .addValue("longUrl", link.longUrl())
                .addValue("normalizedHash", link.normalizedHash())
                .addValue("createdAt", offset(link.createdAt()))
                .addValue("expiresAt", link.expiresAt().map(this::offset).orElse(null))
                .addValue("active", link.active())
                .addValue("createdBy", link.createdBy().orElse(null))
                .addValue("metadata", writeMetadata(link.metadata()));

        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update("""
                    INSERT INTO short_links
                        (code, long_url, normalized_hash, created_at, expires_at, active, created_by, metadata)
                    VALUES
                        (:code, :longUrl, :normalizedHash, :createdAt, :expiresAt, :active, :createdBy, :metadata)
                    """, params, keys, new String[] {"id"});
        } catch (DuplicateKeyException e) {
            throw new CodeConflictException(link.code());
        }

        Number id = keys.getKey();
        if (id == null) {
            // Fallback for drivers that don't surface generated keys on this path.
            return findByCode(link.code()).orElseThrow();
        }
        return ShortLink.rehydrate(id.longValue(), link.code(), link.longUrl(), link.normalizedHash(),
                link.createdAt(), link.expiresAt().orElse(null), link.active(),
                link.createdBy().orElse(null), link.metadata());
    }

    @Override
    public Optional<ShortLink> findByCode(String code) {
        return jdbc.query("SELECT * FROM short_links WHERE code = :code",
                new MapSqlParameterSource("code", code), rowMapper).stream().findFirst();
    }

    @Override
    public boolean existsByCode(String code) {
        Boolean found = jdbc.queryForObject("SELECT COUNT(*) > 0 FROM short_links WHERE code = :code",
                new MapSqlParameterSource("code", code), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    @Override
    public Optional<ShortLink> findReusable(String normalizedHash, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("hash", normalizedHash)
                .addValue("now", offset(now));
        return jdbc.query("""
                SELECT * FROM short_links
                WHERE normalized_hash = :hash AND active = TRUE
                  AND (expires_at IS NULL OR expires_at > :now)
                ORDER BY id ASC
                LIMIT 1
                """, params, rowMapper).stream().findFirst();
    }

    @Override
    public boolean deactivateByCode(String code) {
        return jdbc.update("UPDATE short_links SET active = FALSE WHERE code = :code AND active = TRUE",
                new MapSqlParameterSource("code", code)) > 0;
    }

    @Override
    public List<ShortLink> list(int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query("SELECT * FROM short_links ORDER BY id DESC LIMIT :limit OFFSET :offset", params, rowMapper);
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM short_links", new MapSqlParameterSource(), Long.class);
        return n == null ? 0L : n;
    }

    // --------------------------------------------------------------- mapping

    private ShortLink mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return ShortLink.rehydrate(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("long_url"),
                rs.getString("normalized_hash"),
                rs.getTimestamp("created_at").toInstant(),
                expires == null ? null : expires.toInstant(),
                rs.getBoolean("active"),
                rs.getString("created_by"),
                readMetadata(rs.getString("metadata")));
    }

    private OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata is not serialisable", e);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("stored metadata for a link is not valid JSON, returning empty");
            return Map.of();
        }
    }
}
