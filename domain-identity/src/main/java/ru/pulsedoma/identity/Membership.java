package ru.pulsedoma.identity;

public record Membership(String houseId, String userId, MembershipRole role,
                         VerificationStatus verificationStatus) {}
