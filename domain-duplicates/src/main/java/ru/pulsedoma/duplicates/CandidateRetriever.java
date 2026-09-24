package ru.pulsedoma.duplicates;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

// FR-ISS-003, AC-03: house scope is mandatory before scoring.
public final class CandidateRetriever {
    private final JdbcTemplate jdbc;

    public CandidateRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> search(String houseId, String ftsQuery, int limit) {
        return jdbc.queryForList("SELECT r.id FROM reports_fts f JOIN reports r ON r.rowid = f.rowid " +
                        "WHERE reports_fts MATCH ? AND r.house_id = ? ORDER BY bm25(reports_fts) LIMIT ?",
                String.class, ftsQuery, houseId, Math.min(Math.max(limit, 1), 50));
    }
}
