package ru.pulsedoma.identity;

public record Membership(String houseId, String userId, String role, String verificationStatus) {}
