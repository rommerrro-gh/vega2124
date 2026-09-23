package ru.pulsedoma.issues;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "issues")
public class Issue {
    @Id public String id;
    @Column(name = "house_id", nullable = false) public String houseId;
    public String category;
    @Column(name = "zone_json") public String zoneJson;
    @Enumerated(EnumType.STRING) public IssueStatus status;
    public String priority;
    @Column(name = "sla_due_at") public Instant slaDueAt;
}
