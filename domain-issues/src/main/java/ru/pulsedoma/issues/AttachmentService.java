package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AttachmentService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final AttachmentStore store;

    public AttachmentService(JdbcTemplate jdbc, AttachmentStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public record AttachmentView(String id, String mime) {}
    public record AttachmentData(byte[] bytes, String mime) {}

    @Transactional
    public AttachmentView upload(String reportId, String userId, byte[] bytes, String mime) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES || !valid(bytes, mime)) {
            throw new BusinessException("INVALID_ATTACHMENT", "Only JPEG, PNG, PDF or MP4 up to 10 MB is supported");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reports r JOIN house_memberships m ON m.house_id = r.house_id
                WHERE r.id = ? AND r.author_id = ? AND m.user_id = ?
                  AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE' AND r.withdrawn_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM issue_reports ir WHERE ir.report_id = r.id AND ir.unlinked_at IS NULL)
                """, Integer.class, reportId, userId, userId);
        if (count == null || count == 0) throw new BusinessException("REPORT_NOT_AVAILABLE", "Report is not available for upload");
        Integer attached = jdbc.queryForObject("SELECT COUNT(*) FROM attachments WHERE report_id = ?", Integer.class, reportId);
        if (attached != null && attached >= 5) throw new BusinessException("ATTACHMENT_LIMIT", "Maximum five attachments per report");
        String id = UUID.randomUUID().toString();
        String key = reportId + "/" + id;
        store.put(key, bytes, mime);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO attachments(id, report_id, storage_key, mime, hashes_json, created_at)
                VALUES (?, ?, ?, ?, json_object('sha256', ?), ?)
                """, id, reportId, key, mime, hash, Instant.now().toString());
        return new AttachmentView(id, mime);
    }

    public AttachmentData read(String id, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.storage_key, a.mime FROM attachments a JOIN reports r ON r.id = a.report_id
                WHERE a.id = ? AND (
                  r.author_id = ? OR EXISTS (
                    SELECT 1 FROM issue_reports ir JOIN issue_participants p ON p.issue_id = ir.issue_id
                    WHERE ir.report_id = r.id AND ir.unlinked_at IS NULL AND p.user_id = ?
                  ) OR EXISTS (
                    SELECT 1 FROM house_memberships m WHERE m.house_id = r.house_id
                    AND m.user_id = ? AND m.role = 'DISPATCHER' AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE'
                  ))
                """, id, userId, userId, userId);
        if (rows.isEmpty()) throw new BusinessException("ATTACHMENT_NOT_FOUND", "Attachment is not available");
        Map<String, Object> row = rows.get(0);
        return new AttachmentData(store.get((String) row.get("storage_key")), (String) row.get("mime"));
    }

    public List<AttachmentView> forIssue(String issueId) {
        return jdbc.query("""
                SELECT a.id, a.mime FROM attachments a
                JOIN issue_reports ir ON ir.report_id = a.report_id AND ir.unlinked_at IS NULL
                WHERE ir.issue_id = ? ORDER BY a.created_at
                """, (rs, row) -> new AttachmentView(rs.getString(1), rs.getString(2)), issueId);
    }

    private static boolean valid(byte[] bytes, String mime) {
        return switch (mime == null ? "" : mime) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 255) == 0xff
                    && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff;
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 255) == 0x89
                    && new String(bytes, 1, 3, StandardCharsets.US_ASCII).equals("PNG")
                    && (bytes[4] & 255) == 13 && (bytes[5] & 255) == 10
                    && (bytes[6] & 255) == 26 && (bytes[7] & 255) == 10;
            case "application/pdf" -> bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            case "video/mp4" -> bytes.length >= 8 && new String(bytes, 4, 4, StandardCharsets.US_ASCII).equals("ftyp");
            default -> false;
        };
    }
}
