package ru.pulsedoma.issues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.identity.MembershipRole;
import ru.pulsedoma.identity.VerificationStatus;

@Service
public class ResidentIdentityService {
    private final JdbcTemplate jdbc;

    public ResidentIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MembershipAccess(MembershipRole role, VerificationStatus verificationStatus) {}

    public record HouseView(String id, String address, List<MembershipAccess> memberships) {}

    public record LegacyInvitationView(String houseId, String address, String expiresAt) {}

    public List<HouseView> houses(String userId) {
        LinkedHashMap<String, HouseView> houses = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT h.id, h.address, m.role, m.verification_status FROM houses h
                JOIN house_memberships m ON m.house_id = h.id
                WHERE m.user_id = ? AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE'
                ORDER BY CASE WHEN EXISTS (
                    SELECT 1 FROM guided_demo_sessions s
                    WHERE s.user_id = m.user_id AND s.house_id = h.id AND s.active = 1
                ) THEN 0 ELSE 1 END, h.address, h.id, m.role
                """,
                rs -> {
                    String id = rs.getString("id");
                    String address = rs.getString("address");
                    HouseView house =
                            houses.computeIfAbsent(
                                    id, ignored -> new HouseView(id, address, new ArrayList<>()));
                    house.memberships()
                            .add(
                                    new MembershipAccess(
                                            MembershipRole.valueOf(rs.getString("role")),
                                            VerificationStatus.valueOf(
                                                    rs.getString("verification_status"))));
                },
                userId);
        return List.copyOf(houses.values());
    }

    public List<HouseView> dispatcherHouses(String userId) {
        return houses(userId).stream()
                .filter(
                        house ->
                                house.memberships().stream()
                                        .anyMatch(
                                                access ->
                                                        access.role() == MembershipRole.DISPATCHER))
                .toList();
    }

    public String activeHouse(String userId) {
        List<String> values =
                jdbc.query(
                        "SELECT active_house_id FROM users WHERE id = ?",
                        (rs, row) -> rs.getString(1),
                        userId);
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
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<String> houses =
                jdbc.query(
                        """
                SELECT house_id FROM house_invitations
                WHERE token_hash = ? AND revoked_at IS NULL
                  AND datetime(expires_at) > datetime('now')
                  AND activation_count < activation_limit
                """,
                        (rs, row) -> rs.getString(1),
                        hash);
        if (houses.isEmpty())
            throw new BusinessException(
                    "INVITATION_INVALID", "Invitation is expired or unavailable");
        String houseId = houses.get(0);
        for (HouseView existing : houses(userId)) {
            if (existing.id().equals(houseId)) return existing;
        }
        int changed =
                jdbc.update(
                        """
                UPDATE house_invitations SET activation_count = activation_count + 1
                WHERE token_hash = ? AND revoked_at IS NULL
                  AND datetime(expires_at) > datetime('now')
                  AND activation_count < activation_limit
                """,
                        hash);
        if (changed != 1)
            throw new BusinessException(
                    "INVITATION_INVALID", "Invitation is expired or unavailable");
        jdbc.update(
                """
                INSERT INTO house_memberships(house_id, user_id, role, verification_status)
                VALUES (?, ?, 'RESIDENT', 'VERIFIED')
                ON CONFLICT(house_id, user_id, role) DO UPDATE SET verification_status = 'VERIFIED'
                """,
                houseId,
                userId);
        return houses(userId).stream()
                .filter(h -> h.id().equals(houseId))
                .findFirst()
                .orElseThrow(
                        () -> new BusinessException("INVITATION_INVALID", "House unavailable"));
    }

    public LegacyInvitationView legacyInvitation(String token) {
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return jdbc
                .query(
                        """
                SELECT h.id, h.address, i.expires_at FROM house_invitations i
                JOIN houses h ON h.id = i.house_id WHERE i.token_hash = ? AND i.revoked_at IS NULL
                  AND datetime(i.expires_at) > datetime('now')
                  AND i.activation_count < i.activation_limit
                """,
                        (rs, row) ->
                                new LegacyInvitationView(
                                        rs.getString(1), rs.getString(2), rs.getString(3)),
                        hash)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        "INVITATION_INVALID",
                                        "Invitation is expired or unavailable"));
    }
}
