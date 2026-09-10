package com.example.urlshortener.application.port.out;

import com.example.urlshortener.domain.model.ShortLink;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output port for link persistence (Clean Architecture boundary). The
 * application depends on this interface; the JDBC adapter implements it. Swapping
 * SQLite/H2 for Postgres, or adding a read replica, is a new implementation of
 * this type and nothing else.
 */
public interface ShortLinkRepository {

    /**
     * Insert a new link.
     *
     * @return the stored link with its assigned id
     * @throws CodeConflictException if {@code link.code()} already exists (unique race)
     */
    ShortLink insert(ShortLink link);

    Optional<ShortLink> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * The newest active, non-expired link with this normalized-URL hash, if any.
     * Used for create-time deduplication.
     */
    Optional<ShortLink> findReusable(String normalizedHash, Instant now);

    /** @return true if a row was deactivated (false if it was already inactive / absent) */
    boolean deactivateByCode(String code);

    List<ShortLink> list(int limit, int offset);

    long count();

    /** Thrown by {@link #insert} on a unique-constraint collision. */
    class CodeConflictException extends RuntimeException {
        public CodeConflictException(String code) {
            super("code already exists: " + code);
        }
    }
}
