package ru.pulsedoma.house;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.identity.AccessRole;
import ru.pulsedoma.identity.AccessStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AccessActivationService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public AccessActivationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OrganizationView(String id, String name, List<HouseView> houses) {}
    public record HouseView(String id, String address) {}
    public record AccessView(AccessRole role, String organizationId, String organizationName,
                             String houseId, String houseAddress, AccessStatus status,
                             String userId, String displayName) {}
    public record InvitationView(String id, AccessRole role, String organizationId, String organizationName,
                                 List<HouseView> houses, String inviter, String expiresAt,
                                 int activationLimit, int activationCount, String revokedAt,
                                 boolean acceptedByMe) {}
    public record CreatedInvitation(String token, InvitationView invitation) {}

    public List<OrganizationView> organizations(String actor) {
        requireSystemAdmin(actor);
        String session = demoSession(actor);
        List<String> ids = session == null
                ? jdbc.query("SELECT id FROM organizations ORDER BY name", (rs, row) -> rs.getString(1))
                : jdbc.query("SELECT organization_id FROM guided_demo_organizations WHERE session_id = ?",
                        (rs, row) -> rs.getString(1), session);
        return ids
                .stream().map(id -> new OrganizationView(id, organizationName(id), organizationHouses(id))).toList();
    }

    public List<HouseView> allHouses(String actor) {
        requireSystemAdmin(actor);
        String session = demoSession(actor);
        return session == null
                ? jdbc.query("SELECT id, address FROM houses ORDER BY address", (rs, row) ->
                        new HouseView(rs.getString(1), rs.getString(2)))
                : jdbc.query("""
                        SELECT h.id, h.address FROM houses h JOIN guided_demo_houses d ON d.house_id = h.id
                        WHERE d.session_id = ? ORDER BY h.address
                        """, (rs, row) -> new HouseView(rs.getString(1), rs.getString(2)), session);
    }

    @Transactional
    public OrganizationView createOrganization(String name, String actor) {
        requireSystemAdmin(actor);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organizations(id, type, name) VALUES (?, 'MANAGEMENT_COMPANY', ?)", id, name);
        String session = demoSession(actor);
        if (session != null) jdbc.update("INSERT INTO guided_demo_organizations(session_id, organization_id) VALUES (?, ?)", session, id);
        audit(actor, "ORGANIZATION_CREATED", "organization", id, name);
        return new OrganizationView(id, name, List.of());
    }

    @Transactional
    public HouseView createHouse(String address, String actor) {
        requireSystemAdmin(actor);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO houses(id, address) VALUES (?, ?)", id, address);
        String session = demoSession(actor);
        if (session != null) jdbc.update("INSERT INTO guided_demo_houses(session_id, house_id) VALUES (?, ?)", session, id);
        audit(actor, "HOUSE_CREATED", "house", id, address);
        return new HouseView(id, address);
    }

    @Transactional
    public OrganizationView setOrganizationHouse(String organizationId, String houseId, boolean active, String actor) {
        requireSystemAdmin(actor);
        requireDemoOrganization(organizationId, actor);
        requireDemoHouse(houseId, actor);
        requireOrganization(organizationId);
        requireHouse(houseId);
        if (active) {
            Integer other = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM organization_houses WHERE house_id = ? AND organization_id != ?
                    AND status = 'ACTIVE'
                    """, Integer.class, houseId, organizationId);
            if (other != null && other > 0) throw invalid("House is already assigned to another organization");
            jdbc.update("""
                    INSERT INTO organization_houses(organization_id, house_id, status)
                    VALUES (?, ?, 'ACTIVE') ON CONFLICT(organization_id, house_id)
                    DO UPDATE SET status = 'ACTIVE'
                    """, organizationId, houseId);
            jdbc.update("UPDATE uk_admin_houses SET access_status = 'ACTIVE' WHERE organization_id = ? AND house_id = ? AND access_status = 'SUSPENDED'",
                    organizationId, houseId);
            jdbc.update("""
                    UPDATE house_memberships SET access_status = 'ACTIVE'
                    WHERE source_organization_id = ? AND house_id = ? AND access_status = 'SUSPENDED'
                    """, organizationId, houseId);
        } else {
            jdbc.update("UPDATE organization_houses SET status = 'SUSPENDED' WHERE organization_id = ? AND house_id = ?",
                    organizationId, houseId);
            jdbc.update("UPDATE uk_admin_houses SET access_status = 'SUSPENDED' WHERE organization_id = ? AND house_id = ? AND access_status = 'ACTIVE'",
                    organizationId, houseId);
            jdbc.update("""
                    UPDATE house_memberships SET access_status = 'SUSPENDED'
                    WHERE house_id = ? AND source_organization_id = ?
                      AND role IN ('DISPATCHER', 'HOUSE_ADMIN') AND access_status = 'ACTIVE'
                    """, houseId, organizationId);
            jdbc.update("""
                    UPDATE access_invitations SET revoked_at = ? WHERE organization_id = ? AND revoked_at IS NULL
                      AND id IN (SELECT invitation_id FROM access_invitation_houses WHERE house_id = ?)
                    """, Instant.now().toString(), organizationId, houseId);
            jdbc.update("""
                    UPDATE access_invitations SET revoked_at = ? WHERE role = 'RESIDENT' AND revoked_at IS NULL
                      AND id IN (SELECT invitation_id FROM access_invitation_houses WHERE house_id = ?)
                      AND created_by IN (SELECT user_id FROM house_memberships
                        WHERE house_id = ? AND role = 'HOUSE_ADMIN' AND source_organization_id = ?)
                    """, Instant.now().toString(), houseId, houseId, organizationId);
        }
        audit(actor, active ? "ORGANIZATION_HOUSE_LINKED" : "ORGANIZATION_HOUSE_SUSPENDED",
                "house", houseId, organizationId);
        return new OrganizationView(organizationId, organizationName(organizationId), organizationHouses(organizationId));
    }

    public List<OrganizationView> myOrganizations(String actor) {
        if (isSystemAdmin(actor)) {
            return organizations(actor);
        }
        return jdbc.query("""
                SELECT o.id FROM organizations o JOIN organization_memberships m
                  ON m.organization_id = o.id
                WHERE m.user_id = ? AND m.role = 'UK_ADMIN' AND m.access_status = 'ACTIVE'
                ORDER BY o.name
                """, (rs, row) -> rs.getString(1), actor).stream()
                .map(id -> new OrganizationView(id, organizationName(id), managedHouses(id, actor))).toList();
    }

    public List<AccessView> myAccess(String actor) {
        List<AccessView> result = new ArrayList<>();
        if (isSystemAdmin(actor)) result.add(new AccessView(AccessRole.SYSTEM_ADMIN, null, null, null, null, AccessStatus.ACTIVE, actor, null));
        result.addAll(jdbc.query("""
                SELECT o.id, o.name, m.access_status FROM organization_memberships m
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.user_id = ? AND m.role = 'UK_ADMIN' AND m.access_status = 'ACTIVE'
                """, (rs, row) -> new AccessView(AccessRole.UK_ADMIN, rs.getString(1), rs.getString(2),
                null, null, AccessStatus.valueOf(rs.getString(3)), actor, null), actor));
        result.addAll(jdbc.query("""
                SELECT m.role, h.id, h.address, m.access_status FROM house_memberships m
                JOIN houses h ON h.id = m.house_id
                WHERE m.user_id = ? AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE'
                ORDER BY h.address, m.role
                """, (rs, row) -> new AccessView(AccessRole.valueOf(rs.getString(1)), null, null,
                rs.getString(2), rs.getString(3), AccessStatus.valueOf(rs.getString(4)), actor, null), actor));
        return result;
    }

    @Transactional
    public CreatedInvitation createInvitation(AccessRole role, String organizationId, List<String> houseIds,
                                               int days, int activationLimit, String actor) {
        if (role == AccessRole.SYSTEM_ADMIN) throw invalid("System administrator is provisioned separately");
        List<String> houses = houseIds == null ? List.of() : houseIds.stream().distinct().toList();
        if (houses.isEmpty()) throw invalid("Choose at least one house");
        if (role == AccessRole.RESIDENT) {
            if (houses.size() != 1) throw invalid("Resident invitation must have one house");
            if (days < 1 || days > 30 || activationLimit < 1 || activationLimit > 100) throw invalid("Invalid invitation limits");
        } else if (days != 3 || activationLimit != 1) {
            throw invalid("Staff invitations last three days and have one activation");
        }
        if (role == AccessRole.HOUSE_ADMIN && houses.size() != 1) throw invalid("House administrator invitation must have one house");
        authorizeIssuer(role, organizationId, houses, actor);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO access_invitations(id, token_hash, role, organization_id, created_by,
                    expires_at, activation_limit, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, hash(token), role.name(), organizationId, actor,
                Instant.now().plus(Duration.ofDays(days)).toString(), activationLimit, now);
        for (String house : houses) jdbc.update("INSERT INTO access_invitation_houses(invitation_id, house_id) VALUES (?, ?)", id, house);
        audit(actor, "ACCESS_INVITATION_CREATED", "access_invitation", id, role.name());
        return new CreatedInvitation(token, invitation(id, actor));
    }

    public InvitationView preview(String token, String actor) {
        String id = jdbc.query("SELECT id FROM access_invitations WHERE token_hash = ?",
                (rs, row) -> rs.getString(1), hash(token)).stream().findFirst()
                .orElseThrow(() -> invalid("Invitation is unavailable"));
        InvitationView view = invitation(id, actor);
        if (!view.acceptedByMe() && (view.revokedAt() != null || Instant.parse(view.expiresAt()).isBefore(Instant.now())
                || view.activationCount() >= view.activationLimit())) throw invalid("Invitation is expired or unavailable");
        return view;
    }

    @Transactional
    public InvitationView accept(String token, String actor) {
        InvitationView view = preview(token, actor);
        if (view.acceptedByMe()) return view;
        authorizeIssuer(view.role(), view.organizationId(), view.houses().stream().map(HouseView::id).toList(),
                invitationCreator(view.id()));
        int changed = jdbc.update("""
                UPDATE access_invitations SET activation_count = activation_count + 1
                WHERE id = ? AND revoked_at IS NULL AND expires_at > ? AND activation_count < activation_limit
                """, view.id(), Instant.now().toString());
        if (changed != 1) throw invalid("Invitation is expired or unavailable");
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO access_invitation_activations(invitation_id, user_id, accepted_at) VALUES (?, ?, ?)",
                view.id(), actor, now);
        if (view.role() == AccessRole.UK_ADMIN) {
            jdbc.update("""
                    INSERT INTO organization_memberships(organization_id, user_id, role, house_scope, access_status)
                    VALUES (?, ?, 'UK_ADMIN', NULL, 'ACTIVE') ON CONFLICT(organization_id, user_id, role)
                    DO UPDATE SET access_status = 'ACTIVE'
                    """, view.organizationId(), actor);
            for (HouseView house : view.houses()) jdbc.update("""
                    INSERT INTO uk_admin_houses(organization_id, user_id, house_id, access_status)
                    VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT(organization_id, user_id, house_id)
                    DO UPDATE SET access_status = 'ACTIVE'
                    """, view.organizationId(), actor, house.id());
        } else {
            for (HouseView house : view.houses()) jdbc.update("""
                    INSERT INTO house_memberships(house_id, user_id, role, verification_status, access_status, source_organization_id)
                    VALUES (?, ?, ?, 'VERIFIED', 'ACTIVE', ?) ON CONFLICT(house_id, user_id, role)
                    DO UPDATE SET verification_status = 'VERIFIED', access_status = 'ACTIVE',
                                  source_organization_id = excluded.source_organization_id
                    """, house.id(), actor, view.role().name(), view.organizationId());
        }
        audit(actor, "ACCESS_INVITATION_ACCEPTED", "access_invitation", view.id(), view.role().name());
        return invitation(view.id(), actor);
    }

    public List<InvitationView> createdInvitations(String actor) {
        return jdbc.query("SELECT id FROM access_invitations WHERE created_by = ? ORDER BY created_at DESC",
                (rs, row) -> rs.getString(1), actor).stream().map(id -> invitation(id, actor)).toList();
    }

    @Transactional
    public InvitationView revokeInvitation(String id, String actor) {
        InvitationView view = invitation(id, actor);
        if (!actor.equals(invitationCreator(id))) {
            if (!isSystemAdmin(actor)) throw denied();
            requireDemoOrganization(view.organizationId(), actor);
        }
        jdbc.update("UPDATE access_invitations SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Instant.now().toString(), id);
        audit(actor, "ACCESS_INVITATION_REVOKED", "access_invitation", id, view.role().name());
        return invitation(id, actor);
    }

    @Transactional
    public void revokeAccess(AccessRole role, String userId, String organizationId, String houseId, String actor) {
        if (role == AccessRole.SYSTEM_ADMIN) throw invalid("System administrator cannot be revoked here");
        if (actor.equals(userId)) throw invalid("You cannot revoke your own access");
        if (role == AccessRole.UK_ADMIN) {
            if (organizationId == null) throw invalid("Choose organization");
            requireSystemAdmin(actor);
            requireDemoOrganization(organizationId, actor);
            jdbc.update("UPDATE organization_memberships SET access_status = 'REVOKED' WHERE organization_id = ? AND user_id = ? AND role = 'UK_ADMIN'",
                    organizationId, userId);
            jdbc.update("UPDATE uk_admin_houses SET access_status = 'REVOKED' WHERE organization_id = ? AND user_id = ?",
                    organizationId, userId);
            jdbc.update("""
                    UPDATE access_invitations SET revoked_at = ? WHERE created_by = ? AND organization_id = ?
                      AND revoked_at IS NULL AND role IN ('DISPATCHER', 'HOUSE_ADMIN')
                    """, Instant.now().toString(), userId, organizationId);
        } else {
            if (houseId == null) throw invalid("Choose house");
            authorizeIssuer(role, organizationId, List.of(houseId), actor);
            jdbc.update("UPDATE house_memberships SET access_status = 'REVOKED' WHERE house_id = ? AND user_id = ? AND role = ?",
                    houseId, userId, role.name());
            if (role == AccessRole.HOUSE_ADMIN) jdbc.update("""
                    UPDATE access_invitations SET revoked_at = ? WHERE created_by = ? AND revoked_at IS NULL
                      AND role = 'RESIDENT' AND id IN
                        (SELECT invitation_id FROM access_invitation_houses WHERE house_id = ?)
                    """, Instant.now().toString(), userId, houseId);
        }
        audit(actor, "ACCESS_REVOKED", "user", userId, role.name() + ":" + houseId);
    }

    public List<AccessView> managedAccess(String organizationId, String actor) {
        requireUkAdmin(organizationId, actor);
        return jdbc.query("""
                SELECT m.role, h.id, h.address, m.access_status, u.id, u.display_name FROM house_memberships m
                JOIN houses h ON h.id = m.house_id
                JOIN users u ON u.id = m.user_id
                JOIN uk_admin_houses a ON a.house_id = h.id AND a.organization_id = ? AND a.user_id = ?
                WHERE a.access_status = 'ACTIVE' AND m.role IN ('DISPATCHER', 'HOUSE_ADMIN')
                ORDER BY h.address, m.role
                """, (rs, row) -> new AccessView(AccessRole.valueOf(rs.getString(1)), organizationId,
                organizationName(organizationId), rs.getString(2), rs.getString(3),
                AccessStatus.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6)), organizationId, actor);
    }

    public List<AccessView> organizationAdmins(String organizationId, String actor) {
        requireSystemAdmin(actor);
        requireDemoOrganization(organizationId, actor);
        return jdbc.query("""
                SELECT u.id, u.display_name, h.id, h.address,
                       COALESCE(a.access_status, m.access_status)
                FROM organization_memberships m JOIN users u ON u.id = m.user_id
                LEFT JOIN uk_admin_houses a ON a.organization_id = m.organization_id AND a.user_id = m.user_id
                LEFT JOIN houses h ON h.id = a.house_id
                WHERE m.organization_id = ? AND m.role = 'UK_ADMIN'
                ORDER BY u.display_name, h.address
                """, (rs, row) -> new AccessView(AccessRole.UK_ADMIN, organizationId, null,
                rs.getString(3), rs.getString(4), AccessStatus.valueOf(rs.getString(5)),
                rs.getString(1), rs.getString(2)), organizationId);
    }

    public List<AccessView> houseResidents(String houseId, String actor) {
        if (!activeHouseRole(houseId, actor, "HOUSE_ADMIN")) throw denied();
        return jdbc.query("""
                SELECT u.id, u.display_name, m.access_status FROM house_memberships m
                JOIN users u ON u.id = m.user_id
                WHERE m.house_id = ? AND m.role = 'RESIDENT' ORDER BY u.display_name
                """, (rs, row) -> new AccessView(AccessRole.RESIDENT, null, null, houseId, null,
                AccessStatus.valueOf(rs.getString(3)), rs.getString(1), rs.getString(2)), houseId);
    }

    private void authorizeIssuer(AccessRole role, String organizationId, List<String> houses, String actor) {
        if (role == AccessRole.UK_ADMIN) {
            requireSystemAdmin(actor);
            if (organizationId == null) throw invalid("Choose organization");
            requireDemoOrganization(organizationId, actor);
        } else if (role == AccessRole.DISPATCHER || role == AccessRole.HOUSE_ADMIN) {
            requireUkAdmin(organizationId, actor);
        } else if (role == AccessRole.RESIDENT && houses.size() == 1) {
            if (!activeHouseRole(houses.get(0), actor, "HOUSE_ADMIN")) throw denied();
        }
        if (role != AccessRole.RESIDENT) {
            for (String house : houses) {
                if (!organizationHasHouse(organizationId, house)) throw denied();
                if ((role == AccessRole.DISPATCHER || role == AccessRole.HOUSE_ADMIN)
                        && !managesHouse(organizationId, house, actor)) throw denied();
            }
        }
    }

    private boolean organizationHasHouse(String org, String house) {
        if (org == null || house == null) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM organization_houses WHERE organization_id = ? AND house_id = ? AND status = 'ACTIVE'",
                Integer.class, org, house);
        return count != null && count > 0;
    }

    private boolean managesHouse(String org, String house, String actor) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM uk_admin_houses WHERE organization_id = ? AND house_id = ?
                AND user_id = ? AND access_status = 'ACTIVE'
                """, Integer.class, org, house, actor);
        return count != null && count > 0;
    }

    private boolean activeHouseRole(String house, String actor, String role) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships WHERE house_id = ? AND user_id = ?
                AND role = ? AND verification_status = 'VERIFIED' AND access_status = 'ACTIVE'
                """, Integer.class, house, actor, role);
        return count != null && count > 0;
    }

    private void requireUkAdmin(String org, String actor) {
        if (org == null) throw denied();
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM organization_memberships WHERE organization_id = ? AND user_id = ?
                AND role = 'UK_ADMIN' AND access_status = 'ACTIVE'
                """, Integer.class, org, actor);
        if (count == null || count == 0) throw denied();
    }

    private void requireSystemAdmin(String actor) {
        if (!isSystemAdmin(actor)) throw denied();
    }

    private boolean isSystemAdmin(String actor) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM system_administrators WHERE user_id = ?",
                Integer.class, actor);
        return (count != null && count > 0) || demoSession(actor) != null;
    }

    private String demoSession(String actor) {
        return jdbc.query("SELECT id FROM guided_demo_sessions WHERE user_id = ? AND active = 1",
                (rs, row) -> rs.getString(1), actor).stream().findFirst().orElse(null);
    }

    private void requireDemoOrganization(String organizationId, String actor) {
        String session = demoSession(actor);
        if (session == null) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM guided_demo_organizations
                WHERE session_id = ? AND organization_id = ?
                """, Integer.class, session, organizationId);
        if (count == null || count == 0) throw denied();
    }

    private void requireDemoHouse(String houseId, String actor) {
        String session = demoSession(actor);
        if (session == null) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM guided_demo_houses WHERE session_id = ? AND house_id = ?
                """, Integer.class, session, houseId);
        if (count == null || count == 0) throw denied();
    }

    private void requireOrganization(String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, id) == 0)
            throw invalid("Organization not found");
    }

    private void requireHouse(String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM houses WHERE id = ?", Integer.class, id) == 0)
            throw invalid("House not found");
    }

    private List<HouseView> organizationHouses(String org) {
        return jdbc.query("""
                SELECT h.id, h.address FROM houses h JOIN organization_houses oh ON oh.house_id = h.id
                WHERE oh.organization_id = ? AND oh.status = 'ACTIVE' ORDER BY h.address
                """, (rs, row) -> new HouseView(rs.getString(1), rs.getString(2)), org);
    }

    private List<HouseView> managedHouses(String org, String actor) {
        return jdbc.query("""
                SELECT h.id, h.address FROM houses h JOIN uk_admin_houses ah ON ah.house_id = h.id
                JOIN organization_houses oh ON oh.house_id = h.id AND oh.organization_id = ah.organization_id
                WHERE ah.organization_id = ? AND ah.user_id = ? AND ah.access_status = 'ACTIVE'
                  AND oh.status = 'ACTIVE' ORDER BY h.address
                """, (rs, row) -> new HouseView(rs.getString(1), rs.getString(2)), org, actor);
    }

    private String organizationName(String id) {
        return jdbc.queryForObject("SELECT name FROM organizations WHERE id = ?", String.class, id);
    }

    private String invitationCreator(String id) {
        return jdbc.query("SELECT created_by FROM access_invitations WHERE id = ?",
                (rs, row) -> rs.getString(1), id).stream().findFirst()
                .orElseThrow(() -> invalid("Invitation is unavailable"));
    }

    private InvitationView invitation(String id, String actor) {
        InvitationView row = jdbc.query("""
                SELECT i.id, i.role, i.organization_id, o.name AS organization_name,
                       u.display_name AS inviter, i.expires_at, i.activation_limit,
                       i.activation_count, i.revoked_at,
                       EXISTS(SELECT 1 FROM access_invitation_activations a
                              WHERE a.invitation_id = i.id AND a.user_id = ?) AS accepted
                FROM access_invitations i JOIN users u ON u.id = i.created_by
                LEFT JOIN organizations o ON o.id = i.organization_id WHERE i.id = ?
                """, (rs, index) -> new InvitationView(rs.getString("id"),
                AccessRole.valueOf(rs.getString("role")), rs.getString("organization_id"),
                rs.getString("organization_name"), List.of(), rs.getString("inviter"),
                rs.getString("expires_at"), rs.getInt("activation_limit"),
                rs.getInt("activation_count"), rs.getString("revoked_at"), rs.getInt("accepted") != 0),
                actor, id).stream().findFirst().orElseThrow(() -> invalid("Invitation is unavailable"));
        return new InvitationView(row.id(), row.role(), row.organizationId(), row.organizationName(),
                invitationHouses(id), row.inviter(), row.expiresAt(), row.activationLimit(),
                row.activationCount(), row.revokedAt(), row.acceptedByMe());
    }

    private List<HouseView> invitationHouses(String id) {
        return jdbc.query("""
                SELECT h.id, h.address FROM houses h JOIN access_invitation_houses ih ON ih.house_id = h.id
                WHERE ih.invitation_id = ? ORDER BY h.address
                """, (rs, row) -> new HouseView(rs.getString(1), rs.getString(2)), id);
    }

    private void audit(String actor, String action, String entity, String id, String detail) {
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, after_json)
                VALUES (?, ?, ?, ?, ?, json_object('detail', ?))
                """, UUID.randomUUID().toString(), actor, action, entity, id, detail);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException denied() {
        return new BusinessException("HOUSE_ACCESS_DENIED", "Insufficient access");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVITATION_INVALID", message);
    }
}
