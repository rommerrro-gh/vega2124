package ru.pulsedoma.duplicates;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public final class CandidateRetriever {
    private final JdbcTemplate jdbc;

    public CandidateRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DuplicateCandidate> findCandidates(String houseId, String normalizedText,
                                                    String category, Instant startedAt) {
        if (houseId == null || houseId.isBlank() || normalizedText == null || normalizedText.isBlank()) {
            return List.of();
        }
        String match = Arrays.stream(normalizedText.split("\\s+"))
                .filter(token -> token.matches("[\\p{L}\\p{N}]+"))
                .distinct().limit(12)
                .map(token -> "\"" + token + "\"")
                .collect(Collectors.joining(" OR "));
        if (match.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<DuplicateCandidate> found = jdbc.query("""
                SELECT i.id, i.category, i.normalized_text, i.created_at, bm25(issues_fts) AS rank
                FROM issues_fts JOIN issues i ON i.rowid = issues_fts.rowid
                WHERE issues_fts MATCH ? AND i.house_id = ?
                  AND i.status IN ('OPEN', 'IN_PROGRESS', 'ASSIGNED')
                  AND datetime(i.created_at) >= datetime(?)
                  AND datetime(i.created_at) <= datetime(?)
                ORDER BY rank LIMIT 10
                """, (rs, row) -> {
            String candidateText = rs.getString("normalized_text");
            Set<String> input = new HashSet<>(Arrays.asList(normalizedText.split("\\s+")));
            Set<String> other = candidateText == null || candidateText.isBlank()
                    ? Set.of() : new HashSet<>(Arrays.asList(candidateText.split("\\s+")));
            long shared = input.stream().filter(other::contains).count();
            double text = other.isEmpty() ? 0 : (double) shared / (input.size() + other.size() - shared);
            boolean sameCategory = category != null && category.equals(rs.getString("category"));
            Instant created = parseSqliteTime(rs.getString("created_at"));
            double recency = Math.max(0, 1 - (double) Duration.between(created, now).toHours() / (30 * 24));
            double score = category == null || category.isBlank()
                    ? 0.7 * text + 0.3 * recency
                    : 0.4 * (sameCategory ? 1 : 0) + 0.4 * text + 0.2 * recency;
            List<String> reasons = new java.util.ArrayList<>();
            if (sameCategory) reasons.add("same_category");
            if (shared > 0) reasons.add("shared_terms:" + shared);
            if (recency >= 0.8) reasons.add("recent_issue");
            return new DuplicateCandidate(rs.getString("id"), Math.round(score * 10000) / 10000.0,
                    List.copyOf(reasons));
        }, match, houseId, startedAt.toString(), now.toString());
        return found.stream().sorted(Comparator.comparingDouble(DuplicateCandidate::score).reversed()).toList();
    }

    private static Instant parseSqliteTime(String value) {
        if (value.contains("T")) return Instant.parse(value.endsWith("Z") ? value : value + "Z");
        return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }
}
