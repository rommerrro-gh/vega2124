package ru.pulsedoma.issues;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import ru.pulsedoma.duplicates.DuplicateCandidate;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "reports")
public class Report {
    @Id
    public String id;
    @Column(name = "house_id", nullable = false)
    public String houseId;
    @Column(name = "author_id", nullable = false)
    public String authorId;
    @Column(name = "raw_text", nullable = false)
    public String rawText;
    @Column(name = "normalized_json")
    public String normalizedJson;
    @Column(name = "search_text")
    public String searchText;
    @Column(name = "correlation_id")
    public String correlationId;
    public String category;
    @Column(name = "created_at")
    public Instant createdAt;
    @Transient
    public List<DuplicateCandidate> candidates = List.of();
    @Transient
    public String issueId;
}
