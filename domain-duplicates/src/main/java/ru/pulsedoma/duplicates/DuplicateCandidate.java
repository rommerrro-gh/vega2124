package ru.pulsedoma.duplicates;

import java.util.List;

public record DuplicateCandidate(String issueId, double score, List<String> reasons) {
}
