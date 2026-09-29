package ru.pulsedoma;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.house.GuidedDemoService;
import ru.pulsedoma.house.AccessActivationService;
import ru.pulsedoma.issues.CreateReportCommand;
import ru.pulsedoma.issues.ReportService;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("demo")
class GuidedDemoFlowTest {
    private static final Path DB = database();
    private static final String CODE = "test-guided-demo-code";

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("guided-demo.code-sha256", () -> {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(CODE.getBytes(StandardCharsets.UTF_8))); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private static Path database() {
        try { return Files.createTempFile("pulse-guided-demo-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Autowired GuidedDemoService demos;
    @Autowired AccessActivationService access;
    @Autowired ReportService reports;
    @Autowired JdbcTemplate jdbc;

    @Test
    void isolatesRepeatableDemoAndPreservesCandidatePath() {
        jdbc.update("INSERT INTO users(id, max_user_id, display_name, status) VALUES ('judge-a', '801', 'Судья А', 'ACTIVE')");
        jdbc.update("INSERT INTO users(id, max_user_id, display_name, status) VALUES ('judge-b', '802', 'Судья Б', 'ACTIVE')");
        assertThrows(BusinessException.class, () -> demos.activate("judge-a", "wrong"));
        var first = demos.activate("judge-a", CODE);
        assertTrue(first.active());
        assertEquals(first.houseId(), demos.activate("judge-a", CODE).houseId());
        var second = demos.activate("judge-b", CODE);
        assertNotEquals(first.houseId(), second.houseId());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM house_memberships WHERE house_id = ? AND user_id = 'judge-a' AND access_status = 'ACTIVE'", Integer.class, first.houseId()));
        assertEquals(8, access.myAccess("judge-a").size());
        assertEquals(1, access.organizations("judge-a").size());
        assertEquals(2, access.allHouses("judge-a").size());
        assertTrue(access.allHouses("judge-a").stream().anyMatch(h -> h.id().equals(first.houseId())));
        String anotherHouse = access.allHouses("judge-a").stream().map(h -> h.id())
                .filter(id -> !id.equals(first.houseId())).findFirst().orElseThrow();
        assertNotEquals(first.houseId(), anotherHouse);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM house_memberships WHERE house_id = ? AND user_id = 'judge-a' AND access_status = 'ACTIVE'", Integer.class, anotherHouse));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM organization_houses WHERE organization_id = ? AND status = 'ACTIVE'", Integer.class, access.organizations("judge-a").get(0).id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM polls WHERE house_id = ?", Integer.class, anotherHouse));
        assertEquals("5", jdbc.queryForObject("SELECT value_json FROM house_fields WHERE house_id = ? AND key = 'floor_count'", String.class, anotherHouse));
        assertThrows(BusinessException.class, () -> access.setOrganizationHouse(
                access.organizations("judge-a").get(0).id(), second.houseId(), true, "judge-a"));
        access.createOrganization("Вторая тестовая УК", "judge-a");
        assertEquals(2, access.organizations("judge-a").size());
        assertEquals(1, access.organizations("judge-b").size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM issue_participants p JOIN issues i ON i.id = p.issue_id WHERE i.house_id = ? AND p.user_id <> 'judge-a'", Integer.class, first.houseId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM polls WHERE house_id = ?", Integer.class, first.houseId()));
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM house_fields WHERE house_id = ?", Integer.class, first.houseId()));
        assertEquals("312", jdbc.queryForObject("SELECT value_json FROM house_fields WHERE house_id = ? AND key = 'registered_residents_count'", String.class, first.houseId()));
        var report = reports.createReport(new CreateReportCommand(first.houseId(), "judge-a", "Не работает свет в подъезде", "LIGHTING", "Подъезд 1, этаж 2", null));
        assertFalse(report.candidates.isEmpty());
        var restarted = demos.restart("judge-a");
        assertNotEquals(first.houseId(), restarted.houseId());
        assertEquals("REVOKED", jdbc.queryForObject("SELECT access_status FROM house_memberships WHERE house_id = ? AND user_id = 'judge-a' AND role = 'RESIDENT'", String.class, first.houseId()));
        assertEquals("REVOKED", jdbc.queryForObject("SELECT access_status FROM house_memberships WHERE house_id = ? AND user_id = 'judge-a' AND role = 'RESIDENT'", String.class, anotherHouse));
        assertEquals(second.houseId(), demos.status("judge-b").houseId());
        demos.exit("judge-a");
        assertFalse(demos.status("judge-a").active());
        assertEquals(0, access.myAccess("judge-a").size());
        assertThrows(BusinessException.class, () -> access.organizations("judge-a"));
    }
}
