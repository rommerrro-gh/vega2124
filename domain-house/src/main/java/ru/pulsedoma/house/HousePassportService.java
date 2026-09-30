package ru.pulsedoma.house;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.pulsedoma.common.BusinessException;

@Service
public class HousePassportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HouseAdminService admin;

    public HousePassportService(JdbcTemplate jdbc, ObjectMapper mapper, HouseAdminService admin) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.admin = admin;
    }

    public record PassportField(String key, JsonNode value, String source, String sourceUrl,
                                String fetchedAt, String validAt, Double confidence) {}

    public record HousePassport(String id, String address, List<PassportField> fields,
                                List<HouseAdminService.ContactView> contacts) {}

    public HousePassport getPassport(String houseId, String userId) {
        List<String> addresses = jdbc.query("""
                SELECT h.address FROM houses h
                WHERE h.id = ? AND EXISTS (
                  SELECT 1 FROM house_memberships m
                  WHERE m.house_id = h.id AND m.user_id = ?
                    AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE')
                """, (rs, row) -> rs.getString(1), houseId, userId);
        if (addresses.isEmpty()) {
            throw new BusinessException("HOUSE_NOT_FOUND", "House is not available");
        }
        List<PassportField> fields = jdbc.query("""
                SELECT key, value_json, source, source_url, fetched_at, valid_at, confidence
                FROM house_fields WHERE house_id = ? ORDER BY key
                """, (rs, row) -> {
            double confidence = rs.getDouble("confidence");
            boolean missingConfidence = rs.wasNull();
            return new PassportField(rs.getString("key"),
                    parseValue(rs.getString("value_json")), rs.getString("source"),
                    rs.getString("source_url"), rs.getString("fetched_at"),
                    rs.getString("valid_at"), missingConfidence ? null : confidence);
        }, houseId);
        return new HousePassport(houseId, addresses.get(0), fields, admin.contactsForHouse(houseId));
    }

    private JsonNode parseValue(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored house field JSON", e);
        }
    }
}
