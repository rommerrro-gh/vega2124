package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CaseWorkAccess {
    private final JdbcTemplate jdbc;
    public CaseWorkAccess(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean exists(String sql,Object... args){return jdbc.queryForObject(sql,Integer.class,args)>0;}
    public boolean manager(String house,String user){return exists("""
        SELECT count(*) FROM uk_admin_houses a JOIN organization_memberships m ON m.organization_id=a.organization_id AND m.user_id=a.user_id
        JOIN organization_houses h ON h.organization_id=a.organization_id AND h.house_id=a.house_id JOIN users u ON u.id=a.user_id
        WHERE a.house_id=? AND a.user_id=? AND a.access_status='ACTIVE' AND m.role='UK_ADMIN' AND m.access_status='ACTIVE'
        AND h.status='ACTIVE' AND u.status='ACTIVE'
        """,house,user);}
    public boolean staff(String house,String user){return exists("""
        SELECT count(*) FROM workflow_staff s JOIN users u ON u.id=s.user_id
        JOIN organization_houses h ON h.house_id=s.house_id AND h.organization_id=s.organization_id
        WHERE s.house_id=? AND s.user_id=? AND s.active=1 AND h.status='ACTIVE' AND u.status='ACTIVE'
        """,house,user);}
    public boolean staffFunction(String house,String user,CaseFunction function){return exists("""
        SELECT count(*) FROM workflow_staff s JOIN users u ON u.id=s.user_id
        JOIN organization_houses h ON h.house_id=s.house_id AND h.organization_id=s.organization_id
        WHERE s.house_id=? AND s.user_id=? AND s.function=? AND s.active=1 AND h.status='ACTIVE' AND u.status='ACTIVE'
        """,house,user,function.name());}
    public boolean billingEligible(String house,String user){return dispatcher(house,user)||manager(house,user)||member(house,user)&&exists("SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='HOUSE_ADMIN' AND verification_status='VERIFIED' AND access_status='ACTIVE'",house,user);}
    public boolean billing(String house,String user){return billingEligible(house,user)&&exists("SELECT count(*) FROM case_billing_permissions p JOIN organization_houses h ON h.organization_id=p.source_organization_id AND h.house_id=p.house_id WHERE p.house_id=? AND p.user_id=? AND h.status='ACTIVE'",house,user);}
    public boolean member(String house,String user){return exists("""
        SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id WHERE m.house_id=? AND m.user_id=?
        AND m.verification_status='VERIFIED' AND m.access_status='ACTIVE' AND u.status='ACTIVE'
        """,house,user);}
    public boolean eligible(String house,String user,CaseFunction function){return member(house,user)||staffFunction(house,user,function)||function==CaseFunction.BILLING&&billing(house,user);}
    public boolean assigned(String id,String house,String user,CaseFunction... functions){
        for(var f:functions)if(eligible(house,user,f)&&exists("SELECT count(*) FROM case_collaborators c WHERE c.case_id=? AND c.user_id=? AND c.function=? AND (c.source_organization_id IS NULL OR EXISTS (SELECT 1 FROM organization_houses h WHERE h.organization_id=c.source_organization_id AND h.house_id=? AND h.status='ACTIVE'))",id,user,f.name(),house))return true;
        return false;
    }
    public boolean dispatcher(String house,String user){return member(house,user)&&exists("SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='DISPATCHER' AND verification_status='VERIFIED' AND access_status='ACTIVE'",house,user);}
    public boolean coordinator(String id,String house,String user){return dispatcher(house,user)||assigned(id,house,user,CaseFunction.COORDINATOR);}
    public List<ResidentCaseService.Staff> candidates(String house){return jdbc.query("""
        SELECT DISTINCT u.id,u.display_name FROM users u WHERE u.status='ACTIVE' AND (
          EXISTS(SELECT 1 FROM house_memberships m WHERE m.user_id=u.id AND m.house_id=? AND m.access_status='ACTIVE' AND m.verification_status='VERIFIED' AND m.role IN ('DISPATCHER','HOUSE_ADMIN'))
          OR EXISTS(SELECT 1 FROM workflow_staff s JOIN organization_houses h ON h.house_id=s.house_id AND h.organization_id=s.organization_id
            WHERE s.user_id=u.id AND s.house_id=? AND s.active=1 AND h.status='ACTIVE')
          OR EXISTS(SELECT 1 FROM uk_admin_houses a JOIN organization_memberships m ON m.organization_id=a.organization_id AND m.user_id=a.user_id JOIN organization_houses h ON h.house_id=a.house_id AND h.organization_id=a.organization_id WHERE a.user_id=u.id AND a.house_id=? AND a.access_status='ACTIVE' AND m.access_status='ACTIVE' AND m.role='UK_ADMIN' AND h.status='ACTIVE')) ORDER BY u.display_name
        """,(rs,n)->new ResidentCaseService.Staff(rs.getString(1),rs.getString(2),billing(house,rs.getString(1)),billingEligible(house,rs.getString(1))),house,house,house);}
}
