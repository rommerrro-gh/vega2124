package ru.pulsedoma;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.pulsedoma.issues.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("demo")
class DuplicateSearchFlowTest {
    private static final Path DB = database();
    private static Path database() {
        try { return Files.createTempFile("pulse-duplicates-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }
    @Autowired ReportService reports;
    @Autowired MiniAppService issues;
    @Autowired JdbcTemplate jdbc;

    @Test void searchesAcrossFloorsForElevatorsButNotForLighting() {
        String elevator = create("ELEVATOR", "подъезд 3 этаж 2", "Пассажирский лифт застрял между этажами");
        var match = report("ELEVATOR", "подьезд 3 этаж 4", "Застрял лифт между этажами");
        assertTrue(match.candidates.stream().anyMatch(c -> c.issueId().equals(elevator) && c.reasons().contains("elevator_floor_ignored")));
        assertFalse(report("ELEVATOR", "подъезд 4 этаж 2", "Застрял лифт между этажами").candidates.stream().anyMatch(c -> c.issueId().equals(elevator)));
        String lighting = create("LIGHTING", "подъезд 3 этаж 2", "Не горит лампочка у лифта");
        assertFalse(report("LIGHTING", "подъезд 3 этаж 4", "Не горит лампочка у лифта").candidates.stream().anyMatch(c -> c.issueId().equals(lighting)));
        assertTrue(report("LIGHTING", "второй этаж третьего подъезда", "Не горит лампа у лифта").candidates.stream().anyMatch(c -> c.issueId().equals(lighting)));
        assertFalse(report("LIGHTING", "подъезд 3 этаж 2", "Не вымыты окна").candidates.stream().anyMatch(c -> c.issueId().equals(lighting)));
    }

    @Test void excludesOtherHousesCategoriesClosedIssuesAndDifferentOutdoorObjects() {
        String playground = create("PLAYGROUND", "качели во дворе", "Сломаны качели на детской площадке");
        assertTrue(report("PLAYGROUND", "у качелей во дворе", "Качели на площадке сломаны").candidates.stream().anyMatch(c -> c.issueId().equals(playground)));
        assertFalse(report("PLAYGROUND", "горка во дворе", "Сломана горка на детской площадке").candidates.stream().anyMatch(c -> c.issueId().equals(playground)));
        assertFalse(report("OTHER", "качели во дворе", "Сломаны качели на площадке").candidates.stream().anyMatch(c -> c.issueId().equals(playground)));
        jdbc.update("INSERT INTO houses(id,address) VALUES ('duplicate-other-house','Другой дом')");
        jdbc.update("INSERT INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES ('duplicate-other-house','demo-resident-1','RESIDENT','VERIFIED','ACTIVE')");
        var foreign = reports.createReport(new CreateReportCommand("duplicate-other-house", "demo-resident-1", "Сломаны качели на детской площадке", "PLAYGROUND", "качели во дворе", null));
        assertTrue(foreign.candidates.isEmpty());
        jdbc.update("UPDATE issues SET status='CLOSED_CONFIRMED' WHERE id=?", playground);
        assertFalse(report("PLAYGROUND", "качели во дворе", "Сломаны качели на площадке").candidates.stream().anyMatch(c -> c.issueId().equals(playground)));
    }

    private Report report(String category, String location, String text) {
        return reports.createReport(new CreateReportCommand("demo-house-1", "demo-resident-1", text, category, location, null));
    }
    private String create(String category, String location, String text) {
        return issues.createIssue(report(category, location, text).id, "demo-resident-1").id();
    }
}
