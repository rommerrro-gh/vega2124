package ru.pulsedoma.house;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class HouseAdminService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public HouseAdminService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record ContactView(String id, HouseContactType type, String title, String phone,
                              String details, String updatedAt) {}
    public record InvitationView(String id, String expiresAt, int activationLimit,
                                 int activationCount, String revokedAt, String createdAt) {}
    public record CreatedInvitation(String token, InvitationView invitation) {}

    public List<ContactView> contacts(String houseId, String userId) {
        requireMember(houseId, userId);
        return contactsForHouse(houseId);
    }

    List<ContactView> contactsForHouse(String houseId) {
        return jdbc.query("""
                SELECT id, type, title, phone, details, updated_at
                FROM house_contacts WHERE house_id = ? ORDER BY type, title
                """, (rs, row) -> new ContactView(rs.getString("id"),
                HouseContactType.valueOf(rs.getString("type")), rs.getString("title"),
                rs.getString("phone"), rs.getString("details"), rs.getString("updated_at")), houseId);
    }

    @Transactional
    public ContactView createContact(String houseId, String userId, HouseContactType type,
                                     String title, String phone, String details) {
        requireAdmin(houseId, userId);
        ContactView contact = new ContactView(UUID.randomUUID().toString(), type, title.trim(),
                phone.trim(), details == null ? null : details.trim(), Instant.now().toString());
        jdbc.update("""
                INSERT INTO house_contacts(id, house_id, type, title, phone, details, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, contact.id(), houseId, type.name(), contact.title(), contact.phone(),
                contact.details(), contact.updatedAt(), userId);
        audit(userId, "HOUSE_CONTACT_CREATED", "house_contact", contact.id(), null, contact);
        return contact;
    }

    @Transactional
    public ContactView updateContact(String houseId, String contactId, String userId, HouseContactType type,
                                     String title, String phone, String details) {
        requireAdmin(houseId, userId);
        ContactView previous = contact(houseId, contactId);
        ContactView updated = new ContactView(contactId, type, title.trim(), phone.trim(),
                details == null ? null : details.trim(), Instant.now().toString());
        jdbc.update("""
                UPDATE house_contacts SET type = ?, title = ?, phone = ?, details = ?, updated_at = ?, updated_by = ?
                WHERE id = ? AND house_id = ?
                """, type.name(), updated.title(), updated.phone(), updated.details(),
                updated.updatedAt(), userId, contactId, houseId);
        audit(userId, "HOUSE_CONTACT_UPDATED", "house_contact", contactId, previous, updated);
        return updated;
    }

    @Transactional
    public void deleteContact(String houseId, String contactId, String userId) {
        requireAdmin(houseId, userId);
        ContactView previous = contact(houseId, contactId);
        jdbc.update("DELETE FROM house_contacts WHERE id = ? AND house_id = ?", contactId, houseId);
        audit(userId, "HOUSE_CONTACT_DELETED", "house_contact", contactId, previous, null);
    }

    public List<InvitationView> invitations(String houseId, String userId) {
        requireAdmin(houseId, userId);
        return jdbc.query("""
                SELECT id, expires_at, activation_limit, activation_count, revoked_at, created_at
                FROM house_invitations WHERE house_id = ? ORDER BY created_at DESC, id DESC
                """, (rs, row) -> invitation(rs), houseId);
    }

    @Transactional
    public CreatedInvitation createInvitation(String houseId, String userId, int days, int activationLimit) {
        requireAdmin(houseId, userId);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        InvitationView invitation = new InvitationView(id, Instant.now().plus(Duration.ofDays(days)).toString(),
                activationLimit, 0, null, createdAt);
        jdbc.update("""
                INSERT INTO house_invitations(id, house_id, token_hash, expires_at, activation_limit, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, houseId, hash(token), invitation.expiresAt(), activationLimit, createdAt, userId);
        audit(userId, "HOUSE_INVITATION_CREATED", "house_invitation", id, null, invitation);
        return new CreatedInvitation(token, invitation);
    }

    @Transactional
    public InvitationView revokeInvitation(String houseId, String invitationId, String userId) {
        requireAdmin(houseId, userId);
        InvitationView previous = invitation(houseId, invitationId);
        if (previous.revokedAt() == null) {
            jdbc.update("UPDATE house_invitations SET revoked_at = ? WHERE id = ? AND house_id = ?",
                    Instant.now().toString(), invitationId, houseId);
            audit(userId, "HOUSE_INVITATION_REVOKED", "house_invitation", invitationId,
                    previous, invitation(houseId, invitationId));
        }
        return invitation(houseId, invitationId);
    }

    private ContactView contact(String houseId, String contactId) {
        return jdbc.query("""
                SELECT id, type, title, phone, details, updated_at
                FROM house_contacts WHERE id = ? AND house_id = ?
                """, (rs, row) -> new ContactView(rs.getString("id"),
                HouseContactType.valueOf(rs.getString("type")), rs.getString("title"),
                rs.getString("phone"), rs.getString("details"), rs.getString("updated_at")),
                contactId, houseId).stream().findFirst()
                .orElseThrow(() -> new BusinessException("CONTACT_NOT_FOUND", "Contact is not available"));
    }

    private InvitationView invitation(String houseId, String invitationId) {
        return jdbc.query("""
                SELECT id, expires_at, activation_limit, activation_count, revoked_at, created_at
                FROM house_invitations WHERE id = ? AND house_id = ?
                """, (rs, row) -> invitation(rs), invitationId, houseId).stream().findFirst()
                .orElseThrow(() -> new BusinessException("INVITATION_NOT_FOUND", "Invitation is not available"));
    }

    private InvitationView invitation(java.sql.ResultSet rs) throws java.sql.SQLException {
        String createdAt = rs.getString("created_at");
        if (createdAt != null && createdAt.length() == 19 && createdAt.charAt(10) == ' ') {
            createdAt = createdAt.replace(' ', 'T') + "Z";
        }
        return new InvitationView(rs.getString("id"), rs.getString("expires_at"),
                rs.getInt("activation_limit"), rs.getInt("activation_count"),
                rs.getString("revoked_at"), createdAt);
    }

    private void requireMember(String houseId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships WHERE house_id = ? AND user_id = ?
                  AND verification_status = 'VERIFIED'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_NOT_FOUND", "House is not available");
    }

    private void requireAdmin(String houseId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships WHERE house_id = ? AND user_id = ?
                  AND role = 'HOUSE_ADMIN' AND verification_status = 'VERIFIED'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_ACCESS_DENIED", "House admin access required");
    }

    private void audit(String actorId, String action, String entity, String entityId,
                       Object before, Object after) {
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, before_json, after_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), actorId, action, entity, entityId,
                json(before), json(after));
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize audit entry", e);
        }
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
