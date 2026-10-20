package ru.pulsedoma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class CaseMigrationUpgradeTest {
    @Test void upgradesV13WithResidentCaseAndHistoryIntact() throws Exception {
        String url="jdbc:sqlite:"+Files.createTempFile("pulse-bot-upgrade-", ".db");
        Flyway.configure().dataSource(url,"","").target("13").load().migrate();
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            s.execute("INSERT INTO users(id,display_name,status) VALUES ('resident','Житель','ACTIVE')");
            s.execute("INSERT INTO houses(id,address) VALUES ('house','Дом')");
            s.execute("INSERT INTO resident_cases(id,house_id,author_id,kind,status,description,client_request_id,request_hash,next_step,created_at,updated_at) VALUES ('case','house','resident','PAYMENT','WAITING_RESIDENT','Вопрос по квитанции','request','hash','Уточнение','2026-10-20','2026-10-20')");
            s.execute("INSERT INTO case_events(id,case_id,actor_id,type,text,created_at) VALUES ('event','case','resident','MESSAGE','История сохранена','2026-10-20')");
        }
        assertEquals(1,Flyway.configure().dataSource(url,"","").load().migrate().migrationsExecuted);
        try(var c=DriverManager.getConnection(url);var s=c.createStatement();var r=s.executeQuery("SELECT c.status,e.text FROM resident_cases c JOIN case_events e ON e.case_id=c.id")) {
            assertTrue(r.next());assertEquals("WAITING_RESIDENT",r.getString(1));assertEquals("История сохранена",r.getString(2));
        }
    }
    @Test void upgradesSubmissionDatabaseWithoutChangingExistingRecords() throws Exception {
        var path=Files.createTempFile("pulse-case-upgrade-", ".db");
        String url="jdbc:sqlite:"+path;
        Flyway.configure().dataSource(url,"","").target("12").load().migrate();
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            s.execute("INSERT INTO users(id,display_name,status) VALUES ('existing-user','Существующий житель','ACTIVE')");
            s.execute("INSERT INTO houses(id,address) VALUES ('existing-house','Существующий дом')");
            s.execute("INSERT INTO reports(id,house_id,author_id,raw_text,created_at) VALUES ('existing-report','existing-house','existing-user','Сохранённое обращение','2026-10-01T00:00:00Z')");
        }
        var updated=Flyway.configure().dataSource(url,"","").load();
        assertEquals(2,updated.migrate().migrationsExecuted);
        updated.validate();
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            try(var rs=s.executeQuery("SELECT raw_text FROM reports WHERE id='existing-report'")) {assertTrue(rs.next());assertEquals("Сохранённое обращение",rs.getString(1));}
            try(var rs=s.executeQuery("SELECT count(*) FROM resident_cases")) {assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
            try(var rs=s.executeQuery("PRAGMA integrity_check")) {assertTrue(rs.next());assertEquals("ok",rs.getString(1));}
        }
    }
}
