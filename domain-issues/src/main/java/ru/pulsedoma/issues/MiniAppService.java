package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MiniAppService {
    private final JdbcTemplate jdbc;
    private final AttachmentService attachments;

    public MiniAppService(JdbcTemplate jdbc, AttachmentService attachments) {
        this.jdbc = jdbc;
        this.attachments = attachments;
    }

    public record HouseView(String id, String address) {}
    public record IssueView(String id, String houseId, String address, String category,
                            IssueStatus status, String description, String location,
                            String occurredAt, int participants,
                            List<AttachmentService.AttachmentView> attachments) {}

    public List<HouseView> houses(String userId) {
        return jdbc.query("""
                SELECT DISTINCT h.id, h.address FROM houses h
                JOIN house_memberships m ON m.house_id = h.id
                WHERE m.user_id = ? AND m.verification_status = 'VERIFIED'
                ORDER BY h.address
                """, (rs, row) -> new HouseView(rs.getString(1), rs.getString(2)), userId);
    }

    @Transactional
    public HouseView acceptInvitation(String token, String userId) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<String> houses = jdbc.query("""
                SELECT house_id FROM house_invitations
                WHERE token_hash = ? AND revoked_at IS NULL
                  AND datetime(expires_at) > datetime('now')
                  AND activation_count < activation_limit
                """, (rs, row) -> rs.getString(1), hash);
        if (houses.isEmpty()) throw new BusinessException("INVITATION_INVALID", "Invitation is expired or unavailable");
        String houseId = houses.get(0);
        for (HouseView existing : houses(userId)) {
            if (existing.id().equals(houseId)) return existing;
        }
        int changed = jdbc.update("""
                UPDATE house_invitations SET activation_count = activation_count + 1
                WHERE token_hash = ? AND revoked_at IS NULL
                  AND datetime(expires_at) > datetime('now')
                  AND activation_count < activation_limit
                """, hash);
        if (changed != 1) throw new BusinessException("INVITATION_INVALID", "Invitation is expired or unavailable");
        jdbc.update("""
                INSERT INTO house_memberships(house_id, user_id, role, verification_status)
                VALUES (?, ?, 'RESIDENT', 'VERIFIED')
                ON CONFLICT(house_id, user_id, role) DO UPDATE SET verification_status = 'VERIFIED'
                """, houseId, userId);
        return houses(userId).stream().filter(h -> h.id().equals(houseId)).findFirst()
                .orElseThrow(() -> new BusinessException("INVITATION_INVALID", "House unavailable"));
    }

    public IssueView issue(String issueId, String userId) {
        List<IssueView> found = jdbc.query("""
                SELECT i.id, i.house_id, h.address, i.category, i.status,
                       json_extract(i.zone_json, '$.label') AS location,
                       COALESCE((SELECT r.raw_text FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                                 WHERE ir.issue_id = i.id AND ir.unlinked_at IS NULL
                                 ORDER BY ir.linked_at LIMIT 1), '') AS description,
                       (SELECT r.occurred_at FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                        WHERE ir.issue_id = i.id AND ir.unlinked_at IS NULL
                        ORDER BY ir.linked_at LIMIT 1) AS occurred_at,
                       (SELECT COUNT(*) FROM issue_participants p WHERE p.issue_id = i.id) AS participants
                FROM issues i JOIN houses h ON h.id = i.house_id
                JOIN house_memberships m ON m.house_id = i.house_id
                WHERE i.id = ? AND m.user_id = ? AND m.verification_status = 'VERIFIED'
                LIMIT 1
                """, (rs, row) -> new IssueView(rs.getString("id"), rs.getString("house_id"),
                rs.getString("address"), rs.getString("category"), IssueStatus.valueOf(rs.getString("status")),
                rs.getString("description"), rs.getString("location"), rs.getString("occurred_at"),
                rs.getInt("participants"), List.of()), issueId, userId);
        if (found.isEmpty()) throw new BusinessException("ISSUE_NOT_FOUND", "Issue is not available");
        IssueView row = found.get(0);
        return new IssueView(row.id(), row.houseId(), row.address(), row.category(), row.status(),
                row.description(), row.location(), row.occurredAt(), row.participants(), attachments.forIssue(issueId));
    }

    @Transactional
    public IssueView createIssue(String reportId, String userId) {
        Map<String, Object> report = reportForDecision(reportId, userId);
        String issueId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO issues(id, house_id, category, zone_json, status, priority, sla_due_at,
                                   normalized_text, search_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, issueId, report.get("house_id"), report.get("category"), report.get("zone_json"), IssueStatus.DRAFT.name(),
                IssuePriority.NORMAL.name(), Instant.now().plusSeconds(72 * 3600).toString(),
                report.get("search_text"), report.get("search_text"), now, now);
        link(issueId, reportId, userId, "new_issue", now);
        return issue(issueId, userId);
    }

    @Transactional
    public IssueView joinIssue(String issueId, String reportId, String userId) {
        Map<String, Object> report = reportForDecision(reportId, userId);
        IssueView target = issue(issueId, userId);
        if (!target.houseId().equals(report.get("house_id"))) {
            throw new BusinessException("HOUSE_MISMATCH", "Report and issue belong to different houses");
        }
        if (target.status() != IssueStatus.OPEN && target.status() != IssueStatus.ASSIGNED
                && target.status() != IssueStatus.IN_PROGRESS) {
            throw new BusinessException("ISSUE_NOT_ACTIVE", "Issue is not open for joining");
        }
        link(issueId, reportId, userId, "resident_join", Instant.now().toString());
        return issue(issueId, userId);
    }

    public List<IssueView> dispatcherQueue(String houseId, String userId) {
        requireDispatcher(houseId, userId);
        List<String> ids = jdbc.query("""
                SELECT id FROM issues WHERE house_id = ?
                  AND status IN ('DRAFT', 'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED')
                ORDER BY created_at DESC LIMIT 100
                """, (rs, row) -> rs.getString(1), houseId);
        return ids.stream().map(id -> issue(id, userId)).toList();
    }

    public IssueView dispatcherIssue(String issueId, String userId) {
        requireDispatcher(issueHouse(issueId), userId);
        return issue(issueId, userId);
    }

    @Transactional
    public IssueView changeStatus(String issueId, String userId, IssueStatus next, String reason) {
        String houseId = issueHouse(issueId);
        requireDispatcher(houseId, userId);
        IssueView current = issue(issueId, userId);
        boolean allowed = switch (current.status()) {
            case DRAFT -> next == IssueStatus.OPEN;
            case OPEN -> next == IssueStatus.ASSIGNED;
            case ASSIGNED -> next == IssueStatus.IN_PROGRESS;
            case IN_PROGRESS -> next == IssueStatus.RESOLVED;
            default -> false;
        };
        if (!allowed) throw new BusinessException("INVALID_STATUS_TRANSITION", "This status transition is not allowed");
        String now = Instant.now().toString();
        jdbc.update("UPDATE issues SET status = ?, dispatcher_id = ?, updated_at = ? WHERE id = ?",
                next.name(), userId, now, issueId);
        jdbc.update("""
                INSERT INTO status_events(id, issue_id, from_status, to_status, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), issueId, current.status().name(), next.name(), userId, reason, now);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, created_at)
                VALUES (?, ?, 'ISSUE_STATUS_CHANGED', 'issue', ?, ?)
                """, UUID.randomUUID().toString(), userId, issueId, now);
        return issue(issueId, userId);
    }

    private String issueHouse(String issueId) {
        List<String> houses = jdbc.query("SELECT house_id FROM issues WHERE id = ?",
                (rs, row) -> rs.getString(1), issueId);
        if (houses.isEmpty()) throw new BusinessException("ISSUE_NOT_FOUND", "Issue is not available");
        return houses.get(0);
    }

    private void requireDispatcher(String houseId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships
                WHERE house_id = ? AND user_id = ? AND role = 'DISPATCHER'
                  AND verification_status = 'VERIFIED'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_ACCESS_DENIED", "Dispatcher access required");
    }

    private Map<String, Object> reportForDecision(String reportId, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.house_id, r.category, r.search_text, r.zone_json FROM reports r
                JOIN house_memberships m ON m.house_id = r.house_id
                WHERE r.id = ? AND r.author_id = ? AND r.withdrawn_at IS NULL
                  AND m.user_id = ? AND m.verification_status = 'VERIFIED'
                  AND NOT EXISTS (SELECT 1 FROM issue_reports ir WHERE ir.report_id = r.id AND ir.unlinked_at IS NULL)
                LIMIT 1
                """, reportId, userId, userId);
        if (rows.isEmpty()) throw new BusinessException("REPORT_NOT_AVAILABLE", "Report cannot be linked");
        return rows.get(0);
    }

    private void link(String issueId, String reportId, String userId, String reason, String now) {
        jdbc.update("""
                INSERT INTO issue_reports(id, issue_id, report_id, link_reason, score, linked_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), issueId, reportId, reason, 1.0, now);
        jdbc.update("""
                INSERT OR IGNORE INTO issue_participants(issue_id, user_id, confirmation_type, confirmed_at)
                VALUES (?, ?, ?, ?)
                """, issueId, userId, reason, now);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), userId,
                reason.equals("new_issue") ? "ISSUE_DRAFT_CREATED" : "ISSUE_JOINED", "issue", issueId, now);
    }
}
