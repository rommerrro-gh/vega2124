package ru.pulsedoma.issues;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.pulsedoma.duplicates.CandidateRetriever;
import ru.pulsedoma.duplicates.DuplicateCandidate;
import ru.pulsedoma.duplicates.TextNormalizer;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class ReportServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CandidateRetriever retriever = mock(CandidateRetriever.class);
    private final ReportService service = new ReportService(jdbc, new TextNormalizer(), retriever);

    @Test
    void highScoreReturnsCandidatesWithoutDraft() {
        Report report = createWithScore(0.82);
        assertEquals(1, report.candidates.size());
        assertNull(report.issueId);
        assertTrue(auditContains("DUPLICATE_CANDIDATES_FOUND"));
    }

    @Test
    void middleScoreRequiresReviewWithoutDraft() {
        Report report = createWithScore(0.7);
        assertEquals(1, report.candidates.size());
        assertNull(report.issueId);
        assertTrue(auditContains("DUPLICATE_REVIEW_REQUIRED"));
    }

    @Test
    void lowScoreCreatesDraftAndSla() {
        Report report = createWithScore(0.64);
        assertTrue(report.candidates.isEmpty());
        assertNotNull(report.issueId);
        assertTrue(sqlContains("INSERT INTO issues"));
        assertTrue(auditContains("ISSUE_DRAFT_CREATED"));
    }

    private Report createWithScore(double score) {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        when(retriever.findCandidates(anyString(), anyString(), eq("LIGHTING"), any(Instant.class)))
                .thenReturn(List.of(new DuplicateCandidate("issue-1", score, List.of("shared_terms:1"))));
        return service.createReport(new CreateReportCommand("house-1", "user-1", "Свет в подъезде", "LIGHTING"));
    }

    private boolean auditContains(String action) {
        return mockingDetails(jdbc).getInvocations().stream().anyMatch(invocation ->
                invocation.getMethod().getName().equals("update")
                        && invocation.getArguments().length > 3
                        && action.equals(invocation.getArgument(3)));
    }

    private boolean sqlContains(String fragment) {
        return mockingDetails(jdbc).getInvocations().stream().anyMatch(invocation ->
                invocation.getMethod().getName().equals("update")
                        && ((String) invocation.getArgument(0)).contains(fragment));
    }
}
