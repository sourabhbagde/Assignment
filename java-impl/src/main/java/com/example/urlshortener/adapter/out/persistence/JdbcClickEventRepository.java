package com.example.urlshortener.adapter.out.persistence;

import com.example.urlshortener.application.port.out.ClickEventRepository;
import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.model.LinkStatistics.DailyCount;
import com.example.urlshortener.domain.model.LinkStatistics.ValueCount;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC adapter for {@link ClickEventRepository}. Writes go through
 * {@link #saveBatch} in one transaction: raw rows into {@code click_events} plus
 * a per-(link, day) counter bump into {@code click_daily}. Reads serve the stats
 * endpoint straight off the rollup for counts/series and off the raw stream
 * (LIMIT-ed) for the referrer / user-agent breakdowns.
 *
 * <p>Every statement is a fixed string with named parameters — no SQL is built
 * from variables at runtime, so the injection surface is nil.
 */
@Repository
public class JdbcClickEventRepository implements ClickEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcClickEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void saveBatch(List<ClickEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        SqlParameterSource[] rows = new SqlParameterSource[events.size()];
        for (int i = 0; i < events.size(); i++) {
            ClickEvent e = events.get(i);
            rows[i] = new MapSqlParameterSource()
                    .addValue("linkId", e.linkId())
                    .addValue("code", e.code())
                    .addValue("occurredAt", e.occurredAt().atOffset(ZoneOffset.UTC))
                    .addValue("referrer", e.referrer())
                    .addValue("userAgent", e.userAgent())
                    .addValue("ipHash", e.ipHash());
        }

        jdbc.batchUpdate("""
                INSERT INTO click_events (link_id, code, occurred_at, referrer, user_agent, ip_hash)
                VALUES (:linkId, :code, :occurredAt, :referrer, :userAgent, :ipHash)
                """, rows);

        // Aggregate this batch in memory, then one upsert per (link, day).
        Map<String, long[]> perDay = new HashMap<>(); // "linkId|day" -> [linkId, increment]
        for (ClickEvent e : events) {
            perDay.computeIfAbsent(e.linkId() + "|" + e.day(), k -> new long[] {e.linkId(), 0})[1]++;
        }
        for (Map.Entry<String, long[]> entry : perDay.entrySet()) {
            String day = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            var params = new MapSqlParameterSource()
                    .addValue("linkId", entry.getValue()[0])
                    .addValue("day", day)
                    .addValue("inc", entry.getValue()[1]);
            int updated = jdbc.update("""
                    UPDATE click_daily SET clicks = clicks + :inc
                    WHERE link_id = :linkId AND click_day = :day
                    """, params);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO click_daily (link_id, click_day, clicks)
                        VALUES (:linkId, :day, :inc)
                        """, params);
            }
        }
    }

    @Override
    public long totalClicks(long linkId) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(clicks), 0) FROM click_daily WHERE link_id = :id",
                new MapSqlParameterSource("id", linkId), Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public long clicksBetween(long linkId, Instant from, Instant to) {
        var params = new MapSqlParameterSource()
                .addValue("id", linkId)
                .addValue("from", from == null ? null : from.atOffset(ZoneOffset.UTC))
                .addValue("to", to == null ? null : to.atOffset(ZoneOffset.UTC));
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM click_events
                WHERE link_id = :id
                  AND (:from IS NULL OR occurred_at >= :from)
                  AND (:to   IS NULL OR occurred_at <= :to)
                """, params, Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public Instant lastClickAt(long linkId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(occurred_at) FROM click_events WHERE link_id = :id",
                new MapSqlParameterSource("id", linkId), Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    @Override
    public List<DailyCount> dailyCounts(long linkId, String fromDay, String toDay) {
        var params = new MapSqlParameterSource()
                .addValue("id", linkId)
                .addValue("from", fromDay)
                .addValue("to", toDay);
        return jdbc.query("""
                SELECT click_day, clicks FROM click_daily
                WHERE link_id = :id
                  AND (:from IS NULL OR click_day >= :from)
                  AND (:to   IS NULL OR click_day <= :to)
                ORDER BY click_day ASC
                """, params, (rs, i) -> new DailyCount(rs.getString("click_day"), rs.getLong("clicks")));
    }

    // Full, fixed SQL per dimension — no runtime string building of SQL anywhere.
    private static final String TOP_REFERRERS_SQL = """
            SELECT COALESCE(NULLIF(referrer, ''), :label) AS bucket, COUNT(*) AS c
            FROM click_events
            WHERE link_id = :id
              AND (:from IS NULL OR occurred_at >= :from)
              AND (:to   IS NULL OR occurred_at <= :to)
            GROUP BY bucket
            ORDER BY c DESC, bucket ASC
            LIMIT :lim
            """;
    private static final String TOP_USER_AGENTS_SQL = """
            SELECT COALESCE(NULLIF(user_agent, ''), :label) AS bucket, COUNT(*) AS c
            FROM click_events
            WHERE link_id = :id
              AND (:from IS NULL OR occurred_at >= :from)
              AND (:to   IS NULL OR occurred_at <= :to)
            GROUP BY bucket
            ORDER BY c DESC, bucket ASC
            LIMIT :lim
            """;

    @Override
    public List<ValueCount> topReferrers(long linkId, Instant from, Instant to, int limit) {
        return topBy(TOP_REFERRERS_SQL, "(direct)", linkId, from, to, limit);
    }

    @Override
    public List<ValueCount> topUserAgents(long linkId, Instant from, Instant to, int limit) {
        return topBy(TOP_USER_AGENTS_SQL, "(unknown)", linkId, from, to, limit);
    }

    private List<ValueCount> topBy(@NonNull String sql, String nullLabel,
                                   long linkId, Instant from, Instant to, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", linkId)
                .addValue("from", from == null ? null : from.atOffset(ZoneOffset.UTC))
                .addValue("to", to == null ? null : to.atOffset(ZoneOffset.UTC))
                .addValue("label", nullLabel)
                .addValue("lim", Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql, params, (rs, i) -> new ValueCount(rs.getString("bucket"), rs.getLong("c")));
    }
}
