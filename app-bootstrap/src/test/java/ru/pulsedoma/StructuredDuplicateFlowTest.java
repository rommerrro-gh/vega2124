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
import ru.pulsedoma.duplicates.LocationFeatures;
import ru.pulsedoma.duplicates.StructuredLocationMatcher;
import ru.pulsedoma.issues.*;
import ru.pulsedoma.common.BusinessException;
import static ru.pulsedoma.duplicates.LocationFeatures.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("demo")
class StructuredDuplicateFlowTest {
    private static final Path DB = database();
    private static Path database() {
        try { return Files.createTempFile("pulse-structured-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }
    @Autowired ReportService reports;
    @Autowired MiniAppService issues;
    @Autowired JdbcTemplate jdbc;

    @Test void elevatorMatchesWithoutSharedWordsAndPersistsTypedFeatures() {
        var passenger = place(Area.ENTRANCE, 2, null, LiftType.PASSENGER, 1, null, null, null);
        String id = create("ELEVATOR", passenger, "Дверь кабины зажала сумку");
        var result = report("ELEVATOR", passenger, "Невозможно попасть наверх");
        assertTrue(has(result, id));
        var candidate = result.candidates.stream().filter(c -> c.issueId().equals(id)).findFirst().orElseThrow();
        assertTrue(candidate.score() >= .65);
        assertFalse(candidate.reasons().stream().anyMatch(reason -> reason.startsWith("shared_terms:")));
        jdbc.update("INSERT INTO users(id,display_name) VALUES ('structured-neighbor','Другой житель')");
        jdbc.update("INSERT INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES ('demo-house-1','structured-neighbor','RESIDENT','VERIFIED','ACTIVE')");
        assertEquals(id, issues.candidateSummary(id, "structured-neighbor").id());
        assertThrows(BusinessException.class, () -> issues.issue(id, "structured-neighbor"));
        jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE user_id='structured-neighbor'");
        assertThrows(BusinessException.class, () -> issues.candidateSummary(id, "structured-neighbor"));
        assertEquals(2, jdbc.queryForObject("SELECT json_extract(zone_json,'$.entrance') FROM reports WHERE id=?", Integer.class, result.id));
        assertEquals("PASSENGER", jdbc.queryForObject("SELECT json_extract(zone_json,'$.liftType') FROM issues WHERE id=?", String.class, id));
        assertFalse(has(report("ELEVATOR", place(Area.ENTRANCE, 2, null, LiftType.CARGO, 1, null, null, null), "Дверь кабины зажала сумку"), id));
        assertFalse(has(report("ELEVATOR", place(Area.ENTRANCE, 2, null, LiftType.PASSENGER, 2, null, null, null), "Дверь кабины зажала сумку"), id));
        assertFalse(has(report("ELEVATOR", place(Area.ENTRANCE, 3, null, LiftType.PASSENGER, 1, null, null, null), "Дверь кабины зажала сумку"), id));
        assertTrue(has(report("ELEVATOR", place(Area.ENTRANCE, 2, null, LiftType.UNKNOWN, null, null, null, null), "Застрял между этажами"), id));
    }

    @Test void oldPlacesRemainCandidatesAndIndoorAreasFloorsAreSeparated() {
        String legacy = issues.createIssue(reports.createReport(new CreateReportCommand("demo-house-1", "demo-resident-1", "Лампа мигает", "LIGHTING", "подьезд 2, этаж 2", null)).id, "demo-resident-1").id();
        assertTrue(has(report("LIGHTING", place(Area.ENTRANCE, 2, 2, null, null, null, null, null), "Темно возле лестницы"), legacy));
        assertFalse(has(report("LIGHTING", place(Area.ENTRANCE, 2, 3, null, null, null, null, null), "Лампа мигает"), legacy));
        for (String category : new String[] {"WATER", "HEATING"}) {
            var apartment = place(Area.APARTMENT, 2, 2, null, null, null, null, null);
            String id = create(category, apartment, "Локальная неисправность");
            assertFalse(has(report(category, place(Area.ENTRANCE, 2, 2, null, null, null, null, null), "Локальная неисправность"), id));
            assertFalse(has(report(category, place(Area.APARTMENT, 2, 3, null, null, null, null, null), "Локальная неисправность"), id));
        }
    }

    @Test void matchesWholeEntranceAndHouseButSeparatesOutdoorObjectsAndSites() {
        String cleaning = create("ENTRANCE_CLEANING", place(Area.ENTRANCE, 2, null, null, null, Coverage.ENTIRE, null, null), "Грязная лестница");
        assertTrue(has(report("ENTRANCE_CLEANING", place(Area.ENTRANCE, 2, 2, null, null, Coverage.LOCAL, null, null), "Не убрано"), cleaning));
        String heating = create("HEATING", place(Area.WHOLE_HOUSE, null, null, null, null, null, null, null), "Здание остыло");
        assertTrue(has(report("HEATING", place(Area.APARTMENT, 2, 2, null, null, null, null, null), "Радиаторы холодные"), heating));
        String swing = create("PLAYGROUND", place(Area.YARD, null, null, null, null, null, OutdoorObject.SWING, null), "Сиденье оторвано");
        assertFalse(has(report("PLAYGROUND", place(Area.YARD, null, null, null, null, null, OutdoorObject.SLIDE, null), "Сиденье оторвано"), swing));
        assertTrue(has(report("PLAYGROUND", place(Area.YARD, null, null, null, null, null, null, null), "Есть опасная поломка"), swing));
        String waste = create("WASTE_REMOVAL", place(Area.YARD, null, null, null, null, null, null, 1), "Переполнено");
        assertFalse(has(report("WASTE_REMOVAL", place(Area.YARD, null, null, null, null, null, null, 2), "Переполнено"), waste));
        String yard = create("YARD_CLEANING", place(Area.YARD, null, null, null, null, null, null, null), "Много листьев");
        assertTrue(has(report("YARD_CLEANING", place(Area.YARD, null, null, null, null, null, null, null), "Нужно подмести"), yard));
        jdbc.update("UPDATE issues SET created_at=datetime('now','-31 days') WHERE id=?", yard);
        assertFalse(has(report("YARD_CLEANING", place(Area.YARD, null, null, null, null, null, null, null), "Нужно подмести"), yard));
    }

    @Test void validatesRequiredFieldsPassportBoundsAndOtherPlaces() {
        assertThrows(BusinessException.class, () -> report("LIGHTING", place(Area.ENTRANCE, 1, null, null, null, null, null, null), "Не работает свет"));
        assertThrows(BusinessException.class, () -> report("ELEVATOR", place(Area.ENTRANCE, 0, null, LiftType.UNKNOWN, null, null, null, null), "Лифт сломан"));
        jdbc.update("INSERT OR REPLACE INTO house_fields(house_id,key,value_json,source,fetched_at,confidence) VALUES ('demo-house-1','entrance_count','4','test',datetime('now'),1)");
        assertThrows(BusinessException.class, () -> report("ELEVATOR", place(Area.ENTRANCE, 5, null, LiftType.UNKNOWN, null, null, null, null), "Лифт сломан"));
        var matcher = new StructuredLocationMatcher();
        var a = new LocationFeatures(Area.OTHER, null, null, null, null, null, null, null, "Вход со стороны дороги");
        var b = new LocationFeatures(Area.OTHER, null, null, null, null, null, null, null, "Вход со стороны двора");
        a.validate("OTHER");
        assertFalse(matcher.compare("OTHER", a, b).compatible());
    }

    private static LocationFeatures place(Area area, Integer entrance, Integer floor, LiftType lift, Integer liftNumber, Coverage coverage, OutdoorObject object, Integer site) {
        return new LocationFeatures(area, entrance, floor, lift, liftNumber, coverage, object, site, null);
    }
    private Report report(String category, LocationFeatures features, String text) {
        return reports.createReport(new CreateReportCommand("demo-house-1", "demo-resident-1", text, category, null, null, features));
    }
    private String create(String category, LocationFeatures features, String text) {
        return issues.createIssue(report(category, features, text).id, "demo-resident-1").id();
    }
    private static boolean has(Report report, String id) { return report.candidates.stream().anyMatch(c -> c.issueId().equals(id)); }
}
