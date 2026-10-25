package ru.pulsedoma.issues;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CaseWorkAccess {
    private final JdbcTemplate jdbc;

    public CaseWorkAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args) > 0;
    }

    public boolean manager(String house, String user) {
        return exists(
                """
        SELECT count(*) FROM uk_admin_houses a JOIN organization_memberships m ON m.organization_id=a.organization_id AND m.user_id=a.user_id
        JOIN organization_houses h ON h.organization_id=a.organization_id AND h.house_id=a.house_id JOIN users u ON u.id=a.user_id
        WHERE a.house_id=? AND a.user_id=? AND a.access_status='ACTIVE' AND m.role='UK_ADMIN' AND m.access_status='ACTIVE'
        AND h.status='ACTIVE' AND u.status='ACTIVE'
        """,
                house,
                user);
    }

    public boolean staff(String house, String user) {
        return team(house, user, null)
                || exists(
                        """
        SELECT count(*) FROM workflow_staff s JOIN users u ON u.id=s.user_id
        JOIN organization_houses h ON h.house_id=s.house_id AND h.organization_id=s.organization_id
        WHERE s.house_id=? AND s.user_id=? AND s.active=1 AND h.status='ACTIVE' AND u.status='ACTIVE'
        """,
                        house,
                        user);
    }

    public boolean staffFunction(String house, String user, CaseFunction function) {
        return team(house, user, function)
                || exists(
                        """
        SELECT count(*) FROM workflow_staff s JOIN users u ON u.id=s.user_id
        JOIN organization_houses h ON h.house_id=s.house_id AND h.organization_id=s.organization_id
        WHERE s.house_id=? AND s.user_id=? AND s.function=? AND s.active=1 AND h.status='ACTIVE' AND u.status='ACTIVE'
        """,
                        house,
                        user,
                        function.name());
    }

    private boolean team(String house, String user, CaseFunction function) {
        if (function == CaseFunction.BILLING) return false;
        String role = function == CaseFunction.COORDINATOR ? "COORDINATOR" : "SPECIALIST";
        return exists(
                "SELECT count(*) FROM organization_team t JOIN users u ON u.id=t.user_id JOIN organization_work_houses h ON h.organization_id=t.organization_id WHERE h.house_id=? AND h.active=1 AND t.user_id=? AND t.active=1 AND u.status='ACTIVE' AND (? IS NULL OR (t.role=? AND (?='COORDINATOR' OR EXISTS (SELECT 1 FROM organization_team_functions f WHERE f.organization_id=t.organization_id AND f.user_id=t.user_id AND f.function=?))))",
                house,
                user,
                function == null ? null : function.name(),
                role,
                function == null ? null : function.name(),
                function == null ? null : function.name());
    }

    public boolean billingEligible(String house, String user) {
        return dispatcher(house, user)
                || manager(house, user)
                || member(house, user)
                        && exists(
                                "SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='HOUSE_ADMIN' AND verification_status='VERIFIED' AND access_status='ACTIVE'",
                                house,
                                user);
    }

    public boolean billing(String house, String user) {
        return billingEligible(house, user)
                && exists(
                        "SELECT count(*) FROM case_billing_permissions p JOIN organization_houses h ON h.organization_id=p.source_organization_id AND h.house_id=p.house_id WHERE p.house_id=? AND p.user_id=? AND h.status='ACTIVE'",
                        house,
                        user);
    }

    public boolean member(String house, String user) {
        return exists(
                """
        SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id WHERE m.house_id=? AND m.user_id=?
        AND m.verification_status='VERIFIED' AND m.access_status='ACTIVE' AND u.status='ACTIVE'
        """,
                house,
                user);
    }

    public boolean eligible(String house, String user, CaseFunction function) {
        return member(house, user)
                || staffFunction(house, user, function)
                || function == CaseFunction.COORDINATOR && manager(house, user)
                || function == CaseFunction.BILLING && billing(house, user);
    }

    public boolean assigned(String id, String house, String user, CaseFunction... functions) {
        for (var f : functions)
            if (eligible(house, user, f)
                    && exists(
                            "SELECT count(*) FROM case_collaborators c WHERE (c.case_id=? OR (c.case_id=(SELECT s.root_case_id FROM shared_request_members m JOIN shared_requests s ON s.id=m.request_id WHERE m.case_id=?) AND (c.source_organization_id IS NULL OR EXISTS (SELECT 1 FROM organization_houses privacy_scope WHERE privacy_scope.organization_id=c.source_organization_id AND privacy_scope.house_id=? AND privacy_scope.status='ACTIVE')))) AND c.user_id=? AND c.function=? AND (c.source_organization_id IS NULL OR EXISTS (SELECT 1 FROM organization_houses h WHERE h.organization_id=c.source_organization_id AND h.house_id=? AND h.status='ACTIVE') OR EXISTS (SELECT 1 FROM organization_work_houses h JOIN organization_team t ON t.organization_id=h.organization_id JOIN users u ON u.id=t.user_id WHERE h.organization_id=c.source_organization_id AND h.house_id=? AND h.active=1 AND t.user_id=c.user_id AND t.active=1 AND u.status='ACTIVE' AND ((c.function='COORDINATOR' AND t.role='COORDINATOR') OR (t.role='SPECIALIST' AND EXISTS (SELECT 1 FROM organization_team_functions f WHERE f.organization_id=t.organization_id AND f.user_id=t.user_id AND f.function=c.function)))))",
                            id,
                            id,
                            house,
                            user,
                            f.name(),
                            house,
                            house)) return true;
        return false;
    }

    public boolean dispatcher(String house, String user) {
        return member(house, user)
                && exists(
                        "SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='DISPATCHER' AND verification_status='VERIFIED' AND access_status='ACTIVE'",
                        house,
                        user);
    }

    public boolean coordinator(String id, String house, String user) {
        return dispatcher(house, user)
                || assigned(id, house, user, CaseFunction.COORDINATOR)
                        && exists(
                                "SELECT count(*) WHERE NOT EXISTS (SELECT 1 FROM organization_case_owners WHERE case_id=coalesce((SELECT s.root_case_id FROM shared_request_members m JOIN shared_requests s ON s.id=m.request_id WHERE m.case_id=?),?)) OR EXISTS (SELECT 1 FROM organization_case_owners o JOIN case_collaborators a ON a.source_organization_id=o.organization_id WHERE o.case_id=coalesce((SELECT s.root_case_id FROM shared_request_members m JOIN shared_requests s ON s.id=m.request_id WHERE m.case_id=?),?) AND (a.case_id=? OR a.case_id=o.case_id) AND a.user_id=? AND a.function='COORDINATOR')",
                                id,
                                id,
                                id,
                                id,
                                id,
                                user);
    }

    public boolean hasTasks(String caseId, String user, CaseFunction function) {
        String root =
                jdbc
                        .query(
                                "SELECT s.root_case_id FROM shared_request_members m JOIN shared_requests s ON s.id=m.request_id WHERE m.case_id=?",
                                (r, n) -> r.getString(1),
                                caseId)
                        .stream()
                        .findFirst()
                        .orElse(caseId);
        if (function == CaseFunction.ENGINEER)
            return exists(
                    "SELECT count(*) FROM case_inspections WHERE case_id IN (?,?) AND inspector_id=? AND state NOT IN ('COMPLETED','CANCELLED')",
                    caseId,
                    root,
                    user);
        if (function == CaseFunction.CONTRACTOR)
            return exists(
                    "SELECT count(*) FROM case_work_orders WHERE case_id IN (?,?) AND assignee_id=? AND state NOT IN ('ACCEPTED','CANCELLED')",
                    caseId,
                    root,
                    user);
        return false;
    }

    public boolean mayAssign(
            String id, String house, String actor, String target, CaseFunction function) {
        return exists(
                "SELECT count(*) FROM case_collaborators a WHERE a.case_id=? AND a.user_id=? AND a.function='COORDINATOR' AND (a.source_organization_id IS NULL OR EXISTS (SELECT 1 FROM workflow_staff s WHERE s.house_id=? AND s.organization_id=a.source_organization_id AND s.user_id=? AND s.function=? AND s.active=1) OR EXISTS (SELECT 1 FROM organization_team t JOIN organization_team_functions f ON f.organization_id=t.organization_id AND f.user_id=t.user_id WHERE t.organization_id=a.source_organization_id AND t.user_id=? AND t.role='SPECIALIST' AND t.active=1 AND f.function=?) OR EXISTS (SELECT 1 FROM organization_houses h JOIN house_memberships m ON m.house_id=h.house_id WHERE h.organization_id=a.source_organization_id AND h.house_id=? AND h.status='ACTIVE' AND m.user_id=? AND m.access_status='ACTIVE'))",
                id,
                actor,
                house,
                target,
                function.name(),
                target,
                function.name(),
                house,
                target);
    }

    public List<ResidentCaseService.Staff> candidates(String house) {
        return jdbc.query(
                """
        SELECT DISTINCT u.id,u.display_name FROM users u WHERE u.status='ACTIVE' AND (
          EXISTS(SELECT 1 FROM house_memberships m WHERE m.user_id=u.id AND m.house_id=? AND m.access_status='ACTIVE' AND m.verification_status='VERIFIED' AND m.role IN ('DISPATCHER','HOUSE_ADMIN'))
          OR EXISTS(SELECT 1 FROM workflow_staff s JOIN organization_houses h ON h.house_id=s.house_id AND h.organization_id=s.organization_id
            WHERE s.user_id=u.id AND s.house_id=? AND s.active=1 AND h.status='ACTIVE')
          OR EXISTS(SELECT 1 FROM uk_admin_houses a JOIN organization_memberships m ON m.organization_id=a.organization_id AND m.user_id=a.user_id JOIN organization_houses h ON h.house_id=a.house_id AND h.organization_id=a.organization_id WHERE a.user_id=u.id AND a.house_id=? AND a.access_status='ACTIVE' AND m.access_status='ACTIVE' AND m.role='UK_ADMIN' AND h.status='ACTIVE') OR EXISTS(SELECT 1 FROM organization_team t JOIN organization_work_houses h ON h.organization_id=t.organization_id WHERE t.user_id=u.id AND t.active=1 AND t.role<>'ADMIN' AND h.house_id=? AND h.active=1)) ORDER BY u.display_name
        """,
                (rs, n) ->
                        new ResidentCaseService.Staff(
                                rs.getString(1),
                                rs.getString(2),
                                billing(house, rs.getString(1)),
                                billingEligible(house, rs.getString(1))),
                house,
                house,
                house,
                house);
    }
}
