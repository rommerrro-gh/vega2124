package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import ru.pulsedoma.identity.MembershipRole;
import ru.pulsedoma.identity.VerificationStatus;

@Service
public class MiniAppService {
    private final JdbcTemplate jdbc;
    private final AttachmentService attachments;
    private final IssueNotificationService notifications;

    public MiniAppService(JdbcTemplate jdbc, AttachmentService attachments, IssueNotificationService notifications) {
        this.jdbc = jdbc;
        this.attachments = attachments;
        this.notifications = notifications;
    }

    public record MembershipAccess(MembershipRole role, VerificationStatus verificationStatus) {}
    public record HouseView(String id, String address, List<MembershipAccess> memberships) {}
    public record LegacyInvitationView(String houseId, String address, String expiresAt) {}
    public record LinkedReportView(String id, String description, String author, String createdAt) {}
    public record PlannedDateChange(String previousDate, String newDate, boolean previousDateMissed,
                                    String reason, String changedAt) {}
    public record DispatcherComment(String text, IssueStatus status, String changedAt) {}
    public record IssueView(String id, String houseId, String address, String category,
                            IssueStatus status, String description, String location,
                            String occurredAt, String verificationDueAt, String plannedDate,
                            List<PlannedDateChange> plannedDateHistory, int participants,
                            List<AttachmentService.AttachmentView> attachments,
                            List<LinkedReportView> reports, boolean mergedForMe, DispatcherComment dispatcherComment) {}

    public List<IssueView> myIssues(String userId) {
        List<String> ids = jdbc.query("""
                SELECT i.id FROM issues i JOIN issue_participants p ON p.issue_id = i.id
                WHERE p.user_id = ? AND EXISTS (
                  SELECT 1 FROM house_memberships m WHERE m.house_id = i.house_id
                    AND m.user_id = p.user_id AND m.role = 'RESIDENT'
                    AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE')
                ORDER BY i.updated_at DESC LIMIT 100
                """, (rs, row) -> rs.getString(1), userId);
        return ids.stream().map(id -> issue(id, userId)).toList();
    }

    public List<HouseView> houses(String userId) {
        LinkedHashMap<String, HouseView> houses = new LinkedHashMap<>();
        jdbc.query("""
                SELECT h.id, h.address, m.role, m.verification_status FROM houses h
                JOIN house_memberships m ON m.house_id = h.id
                WHERE m.user_id = ? AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE'
                ORDER BY CASE WHEN EXISTS (
                    SELECT 1 FROM guided_demo_sessions s
                    WHERE s.user_id = m.user_id AND s.house_id = h.id AND s.active = 1
                ) THEN 0 ELSE 1 END, h.address, h.id, m.role
                """, rs -> {
            String id = rs.getString("id");
            String address = rs.getString("address");
            HouseView house = houses.computeIfAbsent(id,
                    ignored -> new HouseView(id, address, new ArrayList<>()));
            house.memberships().add(new MembershipAccess(MembershipRole.valueOf(rs.getString("role")),
                    VerificationStatus.valueOf(rs.getString("verification_status"))));
        }, userId);
        return List.copyOf(houses.values());
    }

    public List<HouseView> dispatcherHouses(String userId) {
        return houses(userId).stream().filter(house -> house.memberships().stream()
                .anyMatch(access -> access.role() == MembershipRole.DISPATCHER)).toList();
    }

    public String activeHouse(String userId) {
        List<String> values = jdbc.query("SELECT active_house_id FROM users WHERE id = ?",
                (rs, row) -> rs.getString(1), userId);
        return values.isEmpty() ? null : values.get(0);
    }

    public void setActiveHouse(String houseId, String userId) {
        if (houses(userId).stream().noneMatch(house -> house.id().equals(houseId))) {
            throw new BusinessException("HOUSE_ACCESS_DENIED", "House access required");
        }
        jdbc.update("UPDATE users SET active_house_id = ? WHERE id = ?", houseId, userId);
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

    public LegacyInvitationView legacyInvitation(String token) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return jdbc.query("""
                SELECT h.id, h.address, i.expires_at FROM house_invitations i
                JOIN houses h ON h.id = i.house_id WHERE i.token_hash = ? AND i.revoked_at IS NULL
                  AND datetime(i.expires_at) > datetime('now')
                  AND i.activation_count < i.activation_limit
                """, (rs, row) -> new LegacyInvitationView(rs.getString(1), rs.getString(2), rs.getString(3)), hash)
                .stream().findFirst().orElseThrow(() -> new BusinessException("INVITATION_INVALID", "Invitation is expired or unavailable"));
    }

    public IssueView issue(String issueId, String userId) {
        List<IssueView> found = jdbc.query("""
                SELECT i.id, i.house_id, h.address, i.category, i.status,
                       json_extract(i.zone_json, '$.label') AS location,
                       COALESCE((SELECT r.raw_text FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                                 WHERE ir.issue_id = i.id AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                                 ORDER BY ir.linked_at, ir.id LIMIT 1), '') AS description,
                       (SELECT r.occurred_at FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                        WHERE ir.issue_id = i.id AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                        ORDER BY ir.linked_at, ir.id LIMIT 1) AS occurred_at,
                       i.verification_due_at, i.planned_date,
                       (SELECT COUNT(*) FROM issue_participants p WHERE p.issue_id = i.id) AS participants,
                       EXISTS (SELECT 1 FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                               WHERE ir.issue_id = i.id AND ir.unlinked_at IS NULL
                                 AND r.withdrawn_at IS NULL AND ir.link_reason = 'dispatcher_merge'
                                 AND r.author_id = ?) AS merged_for_me
                FROM issues i JOIN houses h ON h.id = i.house_id
                JOIN house_memberships m ON m.house_id = i.house_id
                WHERE i.id = ? AND m.user_id = ? AND m.verification_status = 'VERIFIED'
                  AND m.access_status = 'ACTIVE' AND
                  (m.role = 'DISPATCHER' OR (m.role = 'RESIDENT' AND EXISTS
                    (SELECT 1 FROM issue_participants p WHERE p.issue_id = i.id AND p.user_id = m.user_id)))
                LIMIT 1
                """, (rs, row) -> new IssueView(rs.getString("id"), rs.getString("house_id"),
                rs.getString("address"), rs.getString("category"), IssueStatus.valueOf(rs.getString("status")),
                rs.getString("description"), rs.getString("location"), rs.getString("occurred_at"),
                rs.getString("verification_due_at"), rs.getString("planned_date"), List.of(),
                rs.getInt("participants"), List.of(), List.of(), rs.getBoolean("merged_for_me"), null),
                userId, issueId, userId);
        if (found.isEmpty()) throw new BusinessException("ISSUE_NOT_FOUND", "Issue is not available");
        IssueView row = found.get(0);
        List<LinkedReportView> reports = jdbc.query("""
                SELECT r.id, r.raw_text, u.display_name, r.created_at
                FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                JOIN users u ON u.id = r.author_id
                WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                  AND (r.author_id = ? OR EXISTS (
                    SELECT 1 FROM house_memberships m WHERE m.house_id = r.house_id
                      AND m.user_id = ? AND m.role = 'DISPATCHER'
                      AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE'))
                ORDER BY r.created_at, r.id
                """, (rs, index) -> new LinkedReportView(rs.getString("id"), rs.getString("raw_text"),
                rs.getString("display_name"), rs.getString("created_at")), issueId, userId, userId);
        List<PlannedDateChange> history = jdbc.query("""
                SELECT previous_date, new_date, previous_date_missed, reason, created_at
                FROM issue_planned_date_events WHERE issue_id = ? ORDER BY created_at DESC, id DESC
                """, (rs, index) -> new PlannedDateChange(rs.getString("previous_date"),
                rs.getString("new_date"), rs.getInt("previous_date_missed") != 0,
                rs.getString("reason"), rs.getString("created_at")), issueId);
        List<DispatcherComment> comments = jdbc.query("""
                SELECT s.reason, s.to_status, s.created_at FROM status_events s
                WHERE s.issue_id = ? AND s.reason IS NOT NULL AND trim(s.reason) != ''
                  AND EXISTS (SELECT 1 FROM audit_events a WHERE a.entity_id = s.issue_id
                    AND a.actor_id = s.actor_id AND a.created_at = s.created_at
                    AND a.action = 'ISSUE_STATUS_CHANGED')
                ORDER BY s.created_at DESC, s.id DESC LIMIT 1
                """, (rs, index) -> new DispatcherComment(rs.getString("reason"),
                IssueStatus.valueOf(rs.getString("to_status")), rs.getString("created_at")), issueId);
        return new IssueView(row.id(), row.houseId(), row.address(), row.category(), row.status(),
                row.description(), row.location(), row.occurredAt(), row.verificationDueAt(),
                row.plannedDate(), history, row.participants(), attachments.forIssue(issueId), reports,
                row.mergedForMe(), comments.isEmpty() ? null : comments.get(0));
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
        List<Map<String, Object>> targets = jdbc.queryForList("SELECT house_id, status FROM issues WHERE id = ?", issueId);
        if (targets.isEmpty()) throw new BusinessException("ISSUE_NOT_FOUND", "Issue is not available");
        Map<String, Object> target = targets.get(0);
        if (!target.get("house_id").equals(report.get("house_id"))) {
            throw new BusinessException("HOUSE_MISMATCH", "Report and issue belong to different houses");
        }
        IssueStatus status = IssueStatus.valueOf((String) target.get("status"));
        if (!editableIssue(status)) {
            throw new BusinessException("ISSUE_NOT_ACTIVE", "Issue is not open for joining");
        }
        link(issueId, reportId, userId, "resident_join", Instant.now().toString());
        return issue(issueId, userId);
    }

    @Transactional
    public void withdrawReport(String reportId, String userId, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, withdrawn_at FROM reports WHERE id = ? AND author_id = ?
                """, reportId, userId);
        if (rows.isEmpty()) throw new BusinessException("REPORT_NOT_FOUND", "Report is not available");
        if (rows.get(0).get("withdrawn_at") != null) {
            throw new BusinessException("REPORT_ALREADY_WITHDRAWN", "Report was already withdrawn");
        }
        List<String> linked = jdbc.query("""
                SELECT issue_id FROM issue_reports WHERE report_id = ? AND unlinked_at IS NULL
                """, (rs, index) -> rs.getString(1), reportId);
        String issueId = linked.isEmpty() ? null : linked.get(0);
        IssueStatus previous = issueId == null ? null : issue(issueId, userId).status();
        if (previous != null && !editableIssue(previous)) {
            throw new BusinessException("REPORT_WITHDRAWAL_CLOSED", "Report cannot be withdrawn after verification begins");
        }
        String now = Instant.now().toString();
        jdbc.update("UPDATE reports SET withdrawn_at = ?, withdraw_reason = ? WHERE id = ?",
                now, reason, reportId);
        if (issueId != null) {
            jdbc.update("UPDATE issue_reports SET unlinked_at = ? WHERE report_id = ? AND unlinked_at IS NULL",
                    now, reportId);
            removeOrphanParticipant(issueId, userId);
            updateAfterReportRemoval(issueId, previous, userId, now);
        }
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, after_json, created_at)
                VALUES (?, ?, 'REPORT_WITHDRAWN', 'report', ?, json_object('issueId', ?, 'reason', ?), ?)
                """, UUID.randomUUID().toString(), userId, reportId, issueId, reason, now);
    }

    @Transactional
    public IssueView mergeIssues(String sourceId, String targetId, String userId) {
        String houseId = issueHouse(sourceId);
        requireDispatcher(houseId, userId);
        if (sourceId.equals(targetId)) throw new BusinessException("SAME_ISSUE", "Choose another issue");
        if (!houseId.equals(issueHouse(targetId))) {
            throw new BusinessException("HOUSE_MISMATCH", "Issues belong to different houses");
        }
        IssueView source = issue(sourceId, userId);
        IssueView target = issue(targetId, userId);
        if (!editableIssue(source.status()) || !editableIssue(target.status())) {
            throw new BusinessException("ISSUE_NOT_ACTIVE", "Only active issues can be merged");
        }
        if (statusRank(source.status()) > statusRank(target.status())) {
            throw new BusinessException("TARGET_STATUS_BEHIND", "Choose a target issue at the same or later work stage");
        }
        if (activeReportCount(sourceId) == 0 || activeReportCount(targetId) == 0) {
            throw new BusinessException("ISSUE_EMPTY", "Both issues need active reports");
        }
        String now = Instant.now().toString();
        List<String> movedReports = jdbc.query("""
                SELECT report_id FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL
                """, (rs, index) -> rs.getString(1), sourceId);
        jdbc.update("UPDATE issue_reports SET unlinked_at = ? WHERE issue_id = ? AND unlinked_at IS NULL",
                now, sourceId);
        for (String reportId : movedReports) {
            jdbc.update("""
                    INSERT INTO issue_reports(id, issue_id, report_id, link_reason, score, linked_at)
                    VALUES (?, ?, ?, 'dispatcher_merge', 1.0, ?)
                    """, UUID.randomUUID().toString(), targetId, reportId, now);
        }
        jdbc.update("""
                INSERT OR IGNORE INTO issue_participants(issue_id, user_id, confirmation_type, confirmed_at)
                SELECT ?, user_id, confirmation_type, confirmed_at
                FROM issue_participants WHERE issue_id = ?
                """, targetId, sourceId);
        jdbc.update("DELETE FROM issue_participants WHERE issue_id = ?", sourceId);
        jdbc.update("UPDATE issues SET status = 'WITHDRAWN', updated_at = ? WHERE id = ?", now, sourceId);
        jdbc.update("UPDATE issues SET updated_at = ? WHERE id = ?", now, targetId);
        statusEvent(sourceId, source.status(), IssueStatus.WITHDRAWN, userId,
                "Объединена с заявкой " + targetId, now);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, after_json, created_at)
                VALUES (?, ?, 'ISSUES_MERGED', 'issue', ?, json_object('targetIssueId', ?), ?)
                """, UUID.randomUUID().toString(), userId, sourceId, targetId, now);
        return issue(targetId, userId);
    }

    @Transactional
    public IssueView splitIssue(String sourceId, String reportId, String reason, String userId) {
        requireDispatcher(issueHouse(sourceId), userId);
        IssueView source = issue(sourceId, userId);
        if (!editableIssue(source.status())) {
            throw new BusinessException("ISSUE_NOT_ACTIVE", "Only active issues can be split");
        }
        if (activeReportCount(sourceId) < 2) {
            throw new BusinessException("ISSUE_NOT_SPLITTABLE", "At least two reports are needed to split an issue");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.house_id, r.author_id, r.category, r.zone_json, r.search_text
                FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                WHERE ir.issue_id = ? AND ir.report_id = ? AND ir.unlinked_at IS NULL
                  AND r.withdrawn_at IS NULL
                """, sourceId, reportId);
        if (rows.isEmpty()) throw new BusinessException("REPORT_NOT_FOUND", "Report is not linked to this issue");
        Map<String, Object> report = rows.get(0);
        String newId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO issues(id, house_id, category, zone_json, status, priority, sla_due_at,
                                   normalized_text, search_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', 'NORMAL', ?, ?, ?, ?, ?)
                """, newId, report.get("house_id"), report.get("category"), report.get("zone_json"),
                Instant.now().plusSeconds(72 * 3600).toString(), report.get("search_text"),
                report.get("search_text"), now, now);
        jdbc.update("UPDATE issue_reports SET unlinked_at = ? WHERE issue_id = ? AND report_id = ? AND unlinked_at IS NULL",
                now, sourceId, reportId);
        jdbc.update("""
                INSERT INTO issue_reports(id, issue_id, report_id, link_reason, score, linked_at)
                VALUES (?, ?, ?, 'dispatcher_split', 1.0, ?)
                """, UUID.randomUUID().toString(), newId, reportId, now);
        String authorId = (String) report.get("author_id");
        jdbc.update("""
                INSERT INTO issue_participants(issue_id, user_id, confirmation_type, confirmed_at)
                VALUES (?, ?, 'dispatcher_split', ?)
                """, newId, authorId, now);
        removeOrphanParticipant(sourceId, authorId);
        refreshIssueFromPrimaryReport(sourceId);
        jdbc.update("UPDATE issues SET updated_at = ? WHERE id = ?", now, sourceId);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, after_json, created_at)
                VALUES (?, ?, 'ISSUE_SPLIT', 'issue', ?, json_object('newIssueId', ?, 'reportId', ?, 'reason', ?), ?)
                """, UUID.randomUUID().toString(), userId, sourceId, newId, reportId, reason, now);
        return issue(newId, userId);
    }

    public List<IssueView> dispatcherQueue(String houseId, String userId) {
        requireDispatcher(houseId, userId);
        List<String> ids = jdbc.query("""
                SELECT id FROM issues WHERE house_id = ?
                  AND status IN ('DRAFT', 'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'VERIFICATION_72H', 'REOPENED', 'REVIEW_REQUIRED')
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
            case REOPENED -> next == IssueStatus.ASSIGNED;
            case REVIEW_REQUIRED -> next == IssueStatus.WITHDRAWN;
            default -> false;
        };
        if (!allowed) throw new BusinessException("INVALID_STATUS_TRANSITION", "This status transition is not allowed");
        String now = Instant.now().toString();
        jdbc.update("UPDATE issues SET status = ?, dispatcher_id = ?, updated_at = ? WHERE id = ?",
                next.name(), userId, now, issueId);
        statusEvent(issueId, current.status(), next, userId, reason, now);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, created_at)
                VALUES (?, ?, 'ISSUE_STATUS_CHANGED', 'issue', ?, ?)
                """, UUID.randomUUID().toString(), userId, issueId, now);
        if (next == IssueStatus.RESOLVED) startVerification(issueId, now);
        return issue(issueId, userId);
    }

    @Transactional
    public IssueView setPlannedDate(String issueId, String userId, LocalDate date, String reason) {
        String houseId = issueHouse(issueId);
        requireDispatcher(houseId, userId);
        IssueView current = issue(issueId, userId);
        if (current.status() == IssueStatus.VERIFICATION_72H
                || current.status() == IssueStatus.CLOSED_CONFIRMED
                || current.status() == IssueStatus.CLOSED_UNCONFIRMED
                || current.status() == IssueStatus.WITHDRAWN) {
            throw new BusinessException("ISSUE_NOT_EDITABLE", "Planned date cannot be changed at this stage");
        }
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        if (date == null || date.isBefore(today)) {
            throw new BusinessException("INVALID_PLANNED_DATE", "Planned date must be today or later");
        }
        String previous = current.plannedDate();
        if (date.toString().equals(previous)) {
            throw new BusinessException("PLANNED_DATE_UNCHANGED", "Planned date is unchanged");
        }
        String trimmedReason = reason == null ? null : reason.trim();
        if (previous != null && (trimmedReason == null || trimmedReason.isEmpty())) {
            throw new BusinessException("REASON_REQUIRED", "Reason is required when changing a planned date");
        }
        if (trimmedReason != null && trimmedReason.length() > 1000) {
            throw new BusinessException("REASON_TOO_LONG", "Reason is too long");
        }
        String eventId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        boolean missed = previous != null && LocalDate.parse(previous).isBefore(today);
        jdbc.update("UPDATE issues SET planned_date = ?, updated_at = ? WHERE id = ?",
                date.toString(), now, issueId);
        jdbc.update("""
                INSERT INTO issue_planned_date_events
                    (id, issue_id, previous_date, new_date, previous_date_missed, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, issueId, previous, date.toString(), missed ? 1 : 0,
                userId, trimmedReason, now);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, after_json, created_at)
                VALUES (?, ?, 'ISSUE_PLANNED_DATE_CHANGED', 'issue', ?, ?, ?)
                """, UUID.randomUUID().toString(), userId, issueId,
                "{\"plannedDate\":\"" + date + "\"}", now);
        String title = current.description().isBlank() ? "Проблема дома" : current.description().strip();
        if (title.length() > 160) title = title.substring(0, 160) + "…";
        DateTimeFormatter displayDate = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        String context = "Заявка «" + title + "».\nДом: " + current.address()
                + (current.location() == null || current.location().isBlank() ? "" : "\nМесто: " + current.location());
        String message = context + "\n" + (previous == null
                ? "Установлен плановый срок: " + date.format(displayDate) + "."
                : "Плановый срок изменён с " + LocalDate.parse(previous).format(displayDate)
                    + " на " + date.format(displayDate) + ". Причина: " + trimmedReason);
        jdbc.update("""
                UPDATE notification_outbox SET cancelled_at = ?
                WHERE issue_id = ? AND kind GLOB 'PLANNED_DATE_*'
                  AND sent_at IS NULL AND cancelled_at IS NULL
                """, now, issueId);
        jdbc.update("""
                INSERT INTO notification_outbox
                    (id, issue_id, verification_round, recipient_user_id, kind, body, next_attempt_at)
                SELECT lower(hex(randomblob(16))), ?, 0, p.user_id, ?, ?, ?
                FROM issue_participants p JOIN users u ON u.id = p.user_id
                WHERE p.issue_id = ? AND u.max_user_id IS NOT NULL AND trim(u.max_user_id) != ''
                """, issueId, "PLANNED_DATE_" + eventId, message, now, issueId);
        return issue(issueId, userId);
    }

    @Transactional
    public IssueView verify(String issueId, String userId, boolean confirmed, String comment) {
        if (!confirmed && (comment == null || comment.isBlank())) {
            throw new BusinessException("COMMENT_REQUIRED", "Describe what remains unresolved");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.status, i.verification_round, i.verification_due_at FROM issues i
                JOIN issue_participants p ON p.issue_id = i.id AND p.user_id = ?
                WHERE i.id = ?
                """, userId, issueId);
        if (rows.isEmpty()) throw new BusinessException("ISSUE_NOT_FOUND", "Issue is not available");
        Map<String, Object> row = rows.get(0);
        if (!IssueStatus.VERIFICATION_72H.name().equals(row.get("status"))) {
            throw new BusinessException("VERIFICATION_NOT_OPEN", "Issue is not awaiting verification");
        }
        Instant now = Instant.now();
        if (now.isAfter(Instant.parse((String) row.get("verification_due_at")))) {
            throw new BusinessException("VERIFICATION_EXPIRED", "Verification period has expired");
        }
        int round = ((Number) row.get("verification_round")).intValue();
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM issue_verification_responses
                WHERE issue_id = ? AND verification_round = ? AND user_id = ?
                """, Integer.class, issueId, round, userId);
        if (existing != null && existing > 0) {
            throw new BusinessException("ALREADY_VERIFIED", "Response has already been recorded");
        }
        jdbc.update("""
                INSERT INTO issue_verification_responses
                    (issue_id, verification_round, user_id, confirmed, comment, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, issueId, round, userId, confirmed ? 1 : 0, comment, now.toString());
        if (!confirmed) {
            updateVerificationStatus(issueId, IssueStatus.REOPENED, userId, comment, now.toString());
        } else {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM issue_participants p
                    WHERE p.issue_id = ? AND NOT EXISTS (
                      SELECT 1 FROM issue_verification_responses r
                      WHERE r.issue_id = p.issue_id AND r.user_id = p.user_id
                        AND r.verification_round = ? AND r.confirmed = 1)
                    """, Integer.class, issueId, round);
            if (waiting != null && waiting == 0) {
                updateVerificationStatus(issueId, IssueStatus.CLOSED_CONFIRMED, userId,
                        "Все участники подтвердили решение", now.toString());
            }
        }
        return issue(issueId, userId);
    }

    @Transactional
    public int closeExpiredVerifications() {
        List<String> expired = jdbc.query("""
                SELECT id FROM issues WHERE status = 'VERIFICATION_72H'
                  AND datetime(verification_due_at) <= datetime('now')
                """, (rs, row) -> rs.getString(1));
        String now = Instant.now().toString();
        for (String id : expired) {
            updateVerificationStatus(id, IssueStatus.CLOSED_UNCONFIRMED, null,
                    "Срок подтверждения истёк", now);
        }
        return expired.size();
    }

    private void startVerification(String issueId, String now) {
        String dueAt = Instant.parse(now).plusSeconds(72 * 3600).toString();
        jdbc.update("""
                UPDATE notification_outbox SET cancelled_at = ?
                WHERE issue_id = ? AND kind GLOB 'PLANNED_DATE_*'
                  AND sent_at IS NULL AND cancelled_at IS NULL
                """, now, issueId);
        jdbc.update("""
                UPDATE issues SET status = 'VERIFICATION_72H', verification_round = verification_round + 1,
                                  verification_due_at = ?, updated_at = ? WHERE id = ?
                """, dueAt, now, issueId);
        statusEvent(issueId, IssueStatus.RESOLVED, IssueStatus.VERIFICATION_72H, null,
                "Ожидается подтверждение жителей", now);
    }

    private void updateVerificationStatus(String issueId, IssueStatus next, String actorId,
                                          String reason, String now) {
        jdbc.update("UPDATE issues SET status = ?, verification_due_at = NULL, updated_at = ? WHERE id = ?",
                next.name(), now, issueId);
        jdbc.update("""
                UPDATE notification_outbox SET cancelled_at = ?
                WHERE issue_id = ? AND sent_at IS NULL AND cancelled_at IS NULL
                  AND (kind = 'VERIFY_RESULT' OR kind GLOB 'PLANNED_DATE_*')
                """, now, issueId);
        statusEvent(issueId, IssueStatus.VERIFICATION_72H, next, actorId, reason, now);
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, created_at)
                VALUES (?, ?, 'ISSUE_VERIFIED', 'issue', ?, ?)
                """, UUID.randomUUID().toString(), actorId, issueId, now);
    }

    private void statusEvent(String issueId, IssueStatus previous, IssueStatus next,
                             String actorId, String reason, String now) {
        String eventId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO status_events(id, issue_id, from_status, to_status, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, issueId, previous.name(), next.name(), actorId, reason, now);
        notifications.statusChanged(eventId, issueId, previous, next, reason, now);
    }

    private boolean editableIssue(IssueStatus status) {
        return status == IssueStatus.DRAFT || status == IssueStatus.OPEN
                || status == IssueStatus.ASSIGNED || status == IssueStatus.IN_PROGRESS
                || status == IssueStatus.REOPENED;
    }

    private int statusRank(IssueStatus status) {
        return switch (status) {
            case DRAFT -> 0;
            case OPEN -> 1;
            case ASSIGNED, REOPENED -> 2;
            case IN_PROGRESS -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

    private int activeReportCount(String issueId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                """, Integer.class, issueId);
    }

    private void removeOrphanParticipant(String issueId, String userId) {
        jdbc.update("""
                DELETE FROM issue_participants WHERE issue_id = ? AND user_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                    WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                      AND r.author_id = ?)
                """, issueId, userId, issueId, userId);
    }

    private void updateAfterReportRemoval(String issueId, IssueStatus previous, String actorId, String now) {
        if (activeReportCount(issueId) == 0) {
            IssueStatus next = previous == IssueStatus.DRAFT || previous == IssueStatus.OPEN
                    ? IssueStatus.WITHDRAWN : IssueStatus.REVIEW_REQUIRED;
            jdbc.update("UPDATE issues SET status = ?, updated_at = ? WHERE id = ?",
                    next.name(), now, issueId);
            statusEvent(issueId, previous, next, actorId, "Последнее обращение отозвано", now);
        } else {
            refreshIssueFromPrimaryReport(issueId);
            jdbc.update("UPDATE issues SET updated_at = ? WHERE id = ?", now, issueId);
        }
    }

    private void refreshIssueFromPrimaryReport(String issueId) {
        jdbc.update("""
                UPDATE issues SET
                  category = (SELECT r.category FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                    WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                    ORDER BY ir.linked_at, ir.id LIMIT 1),
                  zone_json = (SELECT r.zone_json FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                    WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                    ORDER BY ir.linked_at, ir.id LIMIT 1),
                  normalized_text = (SELECT r.search_text FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                    WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                    ORDER BY ir.linked_at, ir.id LIMIT 1),
                  search_text = (SELECT r.search_text FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                    WHERE ir.issue_id = ? AND ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL
                    ORDER BY ir.linked_at, ir.id LIMIT 1)
                WHERE id = ?
                """, issueId, issueId, issueId, issueId, issueId);
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
                  AND verification_status = 'VERIFIED' AND access_status = 'ACTIVE'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_ACCESS_DENIED", "Dispatcher access required");
    }

    private Map<String, Object> reportForDecision(String reportId, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.house_id, r.category, r.search_text, r.zone_json FROM reports r
                JOIN house_memberships m ON m.house_id = r.house_id
                WHERE r.id = ? AND r.author_id = ? AND r.withdrawn_at IS NULL
                  AND m.user_id = ? AND m.role = 'RESIDENT'
                  AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE'
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
