package ru.pulsedoma.house;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class HousePollService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HousePollService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record OptionView(String id, String label, int votes) {}
    public record PollView(String id, String houseId, String question, boolean official,
                           String closesAt, String createdAt, boolean closed,
                           boolean resultsVisible, String myOptionId, List<OptionView> options) {}

    public List<PollView> list(String houseId, String userId) {
        requireReader(houseId, userId);
        return jdbc.query("SELECT id FROM polls WHERE house_id = ? ORDER BY created_at DESC, id DESC",
                (rs, row) -> rs.getString(1), houseId).stream()
                .map(id -> view(houseId, id, userId)).toList();
    }

    @Transactional
    public PollView create(String houseId, String userId, String question, List<String> options,
                           Instant closesAt, boolean resultsHiddenUntilClose) {
        requireAdmin(houseId, userId);
        if (closesAt == null || !closesAt.isAfter(Instant.now())) {
            throw new BusinessException("INVALID_POLL", "Closing time must be in the future");
        }
        String trimmedQuestion = question.trim();
        List<String> labels = options.stream().map(String::trim).toList();
        if (trimmedQuestion.isEmpty() || labels.stream().anyMatch(String::isEmpty)
                || labels.size() != new HashSet<>(labels).size()) {
            throw new BusinessException("INVALID_POLL", "Question and distinct options are required");
        }
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        jdbc.update("""
                INSERT INTO polls(id, house_id, question, is_official, results_hidden_until_close, closes_at, created_at)
                VALUES (?, ?, ?, 0, ?, ?, ?)
                """, id, houseId, trimmedQuestion, resultsHiddenUntilClose ? 1 : 0,
                closesAt.toString(), createdAt);
        for (int i = 0; i < labels.size(); i++) {
            jdbc.update("INSERT INTO poll_options(id, poll_id, label, position) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), id, labels.get(i), i);
        }
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, action, entity, entity_id, after_json)
                VALUES (?, ?, 'HOUSE_POLL_CREATED', 'poll', ?, ?)
                """, UUID.randomUUID().toString(), userId, id,
                json(new PollCreated(houseId, trimmedQuestion, labels, closesAt.toString(), resultsHiddenUntilClose)));
        return view(houseId, id, userId);
    }

    @Transactional
    public PollView vote(String houseId, String pollId, String optionId, String userId) {
        requireResident(houseId, userId);
        List<String> closes = jdbc.query("SELECT closes_at FROM polls WHERE id = ? AND house_id = ?",
                (rs, row) -> rs.getString(1), pollId, houseId);
        if (closes.isEmpty()) throw new BusinessException("POLL_NOT_FOUND", "Poll is not available");
        if (closes.get(0) != null && !Instant.parse(closes.get(0)).isAfter(Instant.now())) {
            throw new BusinessException("POLL_CLOSED", "Poll is closed");
        }
        Integer validOption = jdbc.queryForObject("""
                SELECT COUNT(*) FROM poll_options WHERE id = ? AND poll_id = ?
                """, Integer.class, optionId, pollId);
        if (validOption == null || validOption == 0) {
            throw new BusinessException("POLL_OPTION_NOT_FOUND", "Option is not available");
        }
        int inserted = jdbc.update("""
                INSERT OR IGNORE INTO poll_votes(poll_id, user_id, option_id, voted_at)
                VALUES (?, ?, ?, ?)
                """, pollId, userId, optionId, Instant.now().toString());
        if (inserted == 0) {
            throw new BusinessException("ALREADY_VOTED", "You have already voted");
        }
        return view(houseId, pollId, userId);
    }

    private PollView view(String houseId, String pollId, String userId) {
        PollRow poll = jdbc.query("""
                SELECT id, house_id, question, is_official, results_hidden_until_close, closes_at, created_at
                FROM polls WHERE id = ? AND house_id = ?
                """, (rs, row) -> new PollRow(rs.getString("id"), rs.getString("house_id"),
                rs.getString("question"), rs.getInt("is_official") != 0,
                rs.getInt("results_hidden_until_close") != 0, rs.getString("closes_at"),
                rs.getString("created_at")), pollId, houseId).stream().findFirst()
                .orElseThrow(() -> new BusinessException("POLL_NOT_FOUND", "Poll is not available"));
        boolean closed = poll.closesAt() != null && !Instant.parse(poll.closesAt()).isAfter(Instant.now());
        boolean visible = !poll.resultsHiddenUntilClose() || closed;
        List<String> choices = jdbc.query("SELECT option_id FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                (rs, row) -> rs.getString(1), pollId, userId);
        List<OptionView> options = jdbc.query("""
                SELECT o.id, o.label, COUNT(v.user_id) AS votes FROM poll_options o
                LEFT JOIN poll_votes v ON v.poll_id = o.poll_id AND v.option_id = o.id
                WHERE o.poll_id = ? GROUP BY o.id, o.label, o.position ORDER BY o.position
                """, (rs, row) -> new OptionView(rs.getString("id"), rs.getString("label"),
                visible ? rs.getInt("votes") : 0), pollId);
        return new PollView(poll.id(), poll.houseId(), poll.question(), poll.official(),
                poll.closesAt(), poll.createdAt(), closed, visible,
                choices.isEmpty() ? null : choices.get(0), options);
    }

    private void requireResident(String houseId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships WHERE house_id = ? AND user_id = ?
                  AND role = 'RESIDENT' AND verification_status = 'VERIFIED' AND access_status = 'ACTIVE'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_NOT_FOUND", "House is not available");
    }

    private void requireReader(String houseId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships WHERE house_id = ? AND user_id = ?
                  AND role IN ('RESIDENT', 'HOUSE_ADMIN')
                  AND verification_status = 'VERIFIED' AND access_status = 'ACTIVE'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_NOT_FOUND", "House is not available");
    }

    private void requireAdmin(String houseId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM house_memberships WHERE house_id = ? AND user_id = ?
                  AND role = 'HOUSE_ADMIN' AND verification_status = 'VERIFIED' AND access_status = 'ACTIVE'
                """, Integer.class, houseId, userId);
        if (count == null || count == 0) throw new BusinessException("HOUSE_ACCESS_DENIED", "House admin access required");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize poll audit entry", e);
        }
    }

    private record PollRow(String id, String houseId, String question, boolean official,
                           boolean resultsHiddenUntilClose, String closesAt, String createdAt) {}
    private record PollCreated(String houseId, String question, List<String> options,
                               String closesAt, boolean resultsHiddenUntilClose) {}
}
