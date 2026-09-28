package com.securetravels.crm.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Searchable audit history.
 *
 * <p>Two different match strategies, and {@code matchedBy} reports which one
 * fired, because they fail differently:
 *
 * <ul>
 *   <li><b>fts</b> - Postgres {@code ts_rank} over the generated tsvector. Fast
 *       and index-backed, but it cannot absorb a typo: searching "Ramash" for
 *       "Ramash" style near-misses is not something tsvector does.</li>
 *   <li><b>trigram</b> - {@code pg_trgm} similarity, so it does absorb typos.</li>
 *   <li><b>none</b> - matched by a structured filter (entity, actor, date) with
 *       no text term, not a search failure.</li>
 * </ul>
 *
 * <p>Showing an unqualified relevance number would imply all three are equally
 * meaningful. A rank from a trigram similarity and a rank from ts_rank are not
 * the same scale, so they are not merged into one field.
 */
public record AuditSearchResponse(
        String query,
        SearchMode mode,
        long total,
        int page,
        int size,
        List<Hit> hits) {

    public enum SearchMode {
        /** Free text present: tsvector rank and/or trigram similarity. */
        TEXT,
        /** No free text: structured filters only. */
        STRUCTURED
    }

    public record Hit(
            UUID id,
            String entity,
            UUID entityId,
            String action,
            String field,
            String oldValue,
            String newValue,
            UUID actorId,
            String actorName,
            Instant createdAt,
            Long seq,
            String matchedBy,
            Double ftsRank,
            Double trigramSimilarity) {
    }
}
