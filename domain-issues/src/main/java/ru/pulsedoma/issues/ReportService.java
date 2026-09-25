package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.duplicates.CandidateRetriever;
import ru.pulsedoma.duplicates.DuplicateCandidate;
import ru.pulsedoma.duplicates.TextNormalizer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReportService {
    private static final double CANDIDATE_THRESHOLD = 0.82;
    private static final double REVIEW_THRESHOLD = 0.65;
    private static final Duration CANDIDATE_WINDOW = Duration.ofDays(30);
    private static final Duration DEFAULT_SLA = Duration.ofHours(72);
    private final JdbcTemplate jdbc;
    private final TextNormalizer normalizer;
    private final CandidateRetriever retriever;

    public ReportService(JdbcTemplate jdbc, TextNormalizer normalizer, CandidateRetriever retriever) {
        this.jdbc = jdbc;
        this.normalizer = normalizer;
        this.retriever = retriever;
    }

    @Transactional
    public Report createReport(CreateReportCommand command) {
        if (command == null || command.houseId() == null || command.houseId().isBlank()
                || command.authorId() == null || command.authorId().isBlank()
                || command.text() == null || command.text().isBlank()) {
            throw new BusinessException("INVALID_REPORT", "House, author and text are required");
        }
        String normalized = normalizer.normalize(command.text());
        if (normalized.isBlank()) throw new BusinessException("INVALID_REPORT", "Report text has no searchable terms");
        Integer allowed = jdbc.queryForObject("""
                SELECT count(*) FROM house_memberships
                WHERE house_id = ? AND user_id = ? AND verification_status = 'VERIFIED'
                """, Integer.class, command.houseId(), command.authorId());
        if (allowed == null || allowed == 0) {
            throw new BusinessException("HOUSE_ACCESS_DENIED", "Author is not a verified house member");
        }

        Instant now = Instant.now();
        Report report = new Report();
        report.id = UUID.randomUUID().toString();
        report.correlationId = UUID.randomUUID().toString();
        report.houseId = command.houseId();
        report.authorId = command.authorId();
        report.rawText = command.text();
        report.searchText = normalized;
        report.category = command.category();
        report.createdAt = now;
        jdbc.update("""
                INSERT INTO reports(id, house_id, author_id, raw_text, search_text, category, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, report.id, report.houseId, report.authorId, report.rawText, normalized,
                report.category, report.correlationId, now.toString());
        audit(report.authorId, "REPORT_CREATED", "report", report.id, now);

        List<DuplicateCandidate> candidates = retriever.findCandidates(report.houseId, normalized,
                report.category, now.minus(CANDIDATE_WINDOW));
        double best = candidates.isEmpty() ? 0 : candidates.get(0).score();
        if (best >= CANDIDATE_THRESHOLD) {
            report.candidates = candidates;
            audit(report.authorId, "DUPLICATE_CANDIDATES_FOUND", "report", report.id, now);
        } else if (best >= REVIEW_THRESHOLD) {
            report.candidates = candidates;
            audit(report.authorId, "DUPLICATE_REVIEW_REQUIRED", "report", report.id, now);
        } else {
            String issueId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO issues(id, house_id, category, status, priority, sla_due_at,
                                       normalized_text, search_text, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, issueId, report.houseId, report.category, IssueStatus.DRAFT.name(),
                    IssuePriority.NORMAL.name(), now.plus(DEFAULT_SLA).toString(), normalized,
                    normalized, now.toString(), now.toString());
            jdbc.update("""
                    INSERT INTO issue_reports(id, issue_id, report_id, link_reason, score, linked_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), issueId, report.id, "new_issue", 1.0, now.toString());
            report.issueId = issueId;
            audit(report.authorId, "ISSUE_DRAFT_CREATED", "issue", issueId, now);
        }
        return report;
    }

    private void audit(String actorId, String action, String entity, String entityId, Instant at) {
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), actorId, action, entity, entityId, at.toString());
    }
}
