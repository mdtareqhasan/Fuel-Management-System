package com.fuelplatform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The audit details must always be valid JSON.
 *
 * <p>This is a regression test for a real production failure. The simulator's
 * transport error message contains the URL of the failed request, and that URL
 * is wrapped in double quotes:
 *
 * <pre>
 *   I/O error on POST request for "http://simulator-api:8000/v1/allocations": ...
 * </pre>
 *
 * <p>The audit details used to be built by string concatenation. That message
 * therefore produced invalid JSON, the insert failed, and — because the insert
 * shared a transaction with the command's status update — the failure rolled
 * the status update back. The command stayed {@code APPROVED} when the truth
 * was {@code UNKNOWN}, and the operator was told nothing.
 *
 * <p>The fix is to pass a map and let Jackson serialise it. Jackson cannot
 * produce invalid JSON, so this class of bug cannot come back.
 */
class AuditRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("an error message containing quotes still produces valid JSON")
    void quoteInAnErrorMessageDoesNotBreakTheJson() throws JsonProcessingException {
        String message = "I/O error on POST request for "
                + "\"http://simulator-api:8000/v1/allocations\": Request cancelled";

        String json = serialise(AuditRepository.details(
                "key", "proposal-1-line-1",
                "error", message));

        assertThatCode(() -> objectMapper.readTree(json)).doesNotThrowAnyException();

        // The message must survive intact, not be mangled or truncated.
        assertThat(objectMapper.readTree(json).get("error").asText())
                .isEqualTo(message);
    }

    @Test
    @DisplayName("a newline or a backslash in the details does not break the JSON")
    void controlCharactersDoNotBreakTheJson() throws JsonProcessingException {
        String message = "line one\nline two\ttabbed \\ backslash";

        String json = serialise(AuditRepository.details("error", message));

        assertThatCode(() -> objectMapper.readTree(json)).doesNotThrowAnyException();
        assertThat(objectMapper.readTree(json).get("error").asText()).isEqualTo(message);
    }

    @Test
    @DisplayName("a null value is recorded as null rather than dropped")
    void nullValuesAreKept() throws JsonProcessingException {
        Map<String, Object> details = AuditRepository.details(
                "allocationId", null,
                "key", "proposal-1-line-1");

        String json = serialise(details);

        assertThat(objectMapper.readTree(json).has("allocationId")).isTrue();
        assertThat(objectMapper.readTree(json).get("allocationId").isNull()).isTrue();
    }

    @Test
    @DisplayName("an empty detail set is an empty JSON object, not invalid JSON")
    void emptyDetailsAreValidJson() {
        assertThat(serialise(Map.of())).isEqualTo("{}");
    }

    @Test
    @DisplayName("the details builder keeps the order it was given")
    void detailsKeepTheirOrder() {
        Map<String, Object> details = AuditRepository.details(
                "zebra", 1,
                "alpha", 2,
                "middle", 3);

        assertThat(details.keySet()).containsExactly("zebra", "alpha", "middle");
    }

    /** Serialises the details the way the repository does. */
    private String serialise(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            throw new AssertionError("the details must always be serialisable", e);
        }
    }
}
