package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Enqueues messages in the same transaction as a status change. */
@Service
public class IssueNotificationService {
    private final JdbcTemplate jdbc;
    private enum Kind { STATUS_CHANGE, VERIFY_RESULT }

    public IssueNotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void statusChanged(String eventId, String issueId, IssueStatus previous, IssueStatus next,
                              String reason, String now) {
        // RESOLVED immediately enters verification in the same transaction; send one actionable message.
        if (next == IssueStatus.RESOLVED) return;
        String message = context(issueId) + "\n";
        Kind kind = next == IssueStatus.VERIFICATION_72H ? Kind.VERIFY_RESULT : Kind.STATUS_CHANGE;
        if (kind == Kind.VERIFY_RESULT) {
            message += "Работа отмечена выполненной. Откройте мини-приложение «Пульс дома» и подтвердите результат в течение 72 часов.";
        } else {
            message += "Статус изменён: «" + previous.displayName() + "» → «" + next.displayName() + "».";
            if (reason != null && !reason.isBlank()) {
                message += "\nКомментарий: " + (reason.startsWith("Объединена с заявкой ") ? "Объединена с другой заявкой" : reason.strip());
            }
        }
        boolean removed = next == IssueStatus.WITHDRAWN || next == IssueStatus.REVIEW_REQUIRED;
        jdbc.update("""
                INSERT INTO notification_outbox
                    (id, issue_id, verification_round, recipient_user_id, kind, body, next_attempt_at)
                SELECT lower(hex(randomblob(16))), i.id, i.verification_round, recipients.user_id, ?, ?, ?
                FROM issues i JOIN (
                    SELECT user_id FROM issue_participants WHERE issue_id = ?
                    UNION
                    SELECT r.author_id FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                    WHERE ir.issue_id = ? AND ? = 1 AND ir.unlinked_at = (
                        SELECT MAX(unlinked_at) FROM issue_reports WHERE issue_id = ?)
                ) recipients JOIN users u ON u.id = recipients.user_id
                WHERE i.id = ? AND u.max_user_id IS NOT NULL AND trim(u.max_user_id) != ''
                  AND EXISTS (SELECT 1 FROM house_memberships m WHERE m.house_id = i.house_id
                    AND m.user_id = recipients.user_id AND m.role = 'RESIDENT'
                    AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE')
                """, kind == Kind.VERIFY_RESULT ? kind.name() : kind.name() + "_" + eventId,
                message, now, issueId, issueId, removed ? 1 : 0, issueId, issueId);
    }

    private String context(String issueId) {
        return jdbc.queryForObject("""
                SELECT h.address, json_extract(i.zone_json, '$.label') AS location,
                    COALESCE((SELECT r.raw_text FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
                        WHERE ir.issue_id = i.id
                        ORDER BY CASE WHEN ir.unlinked_at IS NULL AND r.withdrawn_at IS NULL THEN 0 ELSE 1 END,
                            ir.linked_at, ir.id LIMIT 1), 'Проблема дома') AS description
                FROM issues i JOIN houses h ON h.id = i.house_id WHERE i.id = ?
                """, (rs, row) -> {
            String title = rs.getString("description").strip();
            if (title.length() > 160) title = title.substring(0, 160) + "…";
            String location = rs.getString("location");
            return "Заявка «" + title + "».\nДом: " + rs.getString("address")
                    + (location == null || location.isBlank() ? "" : "\nМесто: " + location);
        }, issueId);
    }
}
