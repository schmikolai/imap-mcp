package io.imapmcp.imap.dto;

import java.time.Instant;

/**
 * All fields nullable/optional; absent fields are simply not included in the
 * resulting {@link jakarta.mail.search.SearchTerm}. Free-text fields are
 * always passed through Jakarta Mail's typed search terms
 * (e.g. {@code SubjectTerm}) rather than concatenated into a raw IMAP SEARCH
 * command string.
 */
public record SearchCriteria(String subjectContains, String fromContains, Boolean unseenOnly, Instant since, Integer maxResults) {

    public static final int DEFAULT_MAX_RESULTS = 50;
    public static final int HARD_MAX_RESULTS = 500;

    public int effectiveMaxResults() {
        if (maxResults == null) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(Math.max(maxResults, 1), HARD_MAX_RESULTS);
    }
}
