package ru.pulsedoma.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id
    public String id;
    @Column(name = "max_user_id", unique = true)
    public String maxUserId;
    @Column(name = "display_name")
    public String displayName;
    @Column(name = "phone_enc")
    public String phoneEnc;
    @Enumerated(EnumType.STRING)
    public UserStatus status;
}
