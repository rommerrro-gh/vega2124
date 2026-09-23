package ru.pulsedoma.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity @Table(name = "houses")
public class House {
    @Id public String id;
    public String address;
}
