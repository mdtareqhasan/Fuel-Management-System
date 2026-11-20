package com.fuelplatform.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the three error shapes the simulator actually returns.
 *
 * <p>These are not hypothetical. Every body below was captured from a live
 * {@code asifmahmoud414/bup-fuel-supply-simulator:1.0.0} response on 2026-11-20
 * and is recorded in {@code docs/contract-notes.md} §5.4.
 *
 * <p>The reason this test exists: a Jackson record bound to {@code detail} would
 * deserialise shape 1 and <strong>throw</strong> on shape 2, because {@code detail}
 * is an object in one and an array in the other. That failure would only appear at
 * runtime, on a 422, in production.
 */
class SimulatorErrorParserTest {

    private final SimulatorErrorParser parser = new SimulatorErrorParser(new ObjectMapper());

    @Test
    @DisplayName("shape 1: domain error carries detail as an object")
    void parsesDomainError() {
        String body = """
                {"detail":{"code":"ROUTE_MISMATCH","message":"Route does not connect selected depot and station"}}
                """;

        SimulatorError error = parser.parse(body, 409);

        assertThat(error).isInstanceOf(SimulatorError.Domain.class);
        SimulatorError.Domain domain = (SimulatorError.Domain) error;
        assertThat(domain.code()).isEqualTo("ROUTE_MISMATCH");
        assertThat(domain.message()).isEqualTo("Route does not connect selected depot and station");
        assertThat(domain.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("shape 2: Pydantic 422 carries detail as an array, not an object")
    void parsesValidationErrorWithArrayDetail() {
        String body = """
                {"detail":[{"type":"literal_error","loc":["body","fuel_type"],
                 "msg":"Input should be 'DIESEL', 'PETROL' or 'OCTANE'","input":"Kerosene"}]}
                """;

        SimulatorError error = parser.parse(body, 422);

        assertThat(error).isInstanceOf(SimulatorError.Validation.class);
        SimulatorError.Validation validation = (SimulatorError.Validation) error;
        assertThat(validation.summary()).contains("body.fuel_type");
        assertThat(validation.summary()).contains("DIESEL");
        assertThat(validation.httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("shape 3: injected fault uses a top-level error key, not detail")
    void parsesInjectedFault() {
        String body = """
                {"error":{"code":"FAULT_INJECTED","message":"Simulator API temporarily unavailable."}}
                """;

        SimulatorError error = parser.parse(body, 503);

        assertThat(error).isInstanceOf(SimulatorError.Fault.class);
        SimulatorError.Fault fault = (SimulatorError.Fault) error;
        assertThat(fault.code()).isEqualTo("FAULT_INJECTED");
        assertThat(fault.message()).isEqualTo("Simulator API temporarily unavailable.");
    }

    @Test
    @DisplayName("shape 3 variant: stream_disconnect nests its code under detail")
    void parsesStreamDisconnectFault() {
        String body = """
                {"detail":{"code":"FAULT_INJECTED"}}
                """;

        SimulatorError error = parser.parse(body, 503);

        // detail is an object here, so it parses as a Domain error. The code still
        // identifies it as an injected fault, which is what the retry policy needs.
        assertThat(error).isInstanceOf(SimulatorError.Domain.class);
        assertThat(((SimulatorError.Domain) error).code()).isEqualTo("FAULT_INJECTED");
    }

    @Test
    @DisplayName("a blank body is unparseable, not a silent success")
    void parsesEmptyBody() {
        SimulatorError error = parser.parse("", 500);

        assertThat(error).isInstanceOf(SimulatorError.Unparseable.class);
    }

    @Test
    @DisplayName("a body with neither error nor detail is unparseable")
    void parsesBodyWithoutKnownFields() {
        SimulatorError error = parser.parse("{\"unexpected\": true}", 500);

        assertThat(error).isInstanceOf(SimulatorError.Unparseable.class);
    }

    @Test
    @DisplayName("only faults are retryable; a domain conflict must be re-decided")
    void retryability() {
        assertThat(parser.parse("{\"error\":{\"code\":\"FAULT_INJECTED\"}}", 503).isRetryable()).isTrue();
        assertThat(parser.parse("""
                {"detail":{"code":"DISPATCH_CAPACITY_EXCEEDED","message":"..."}}
                """, 409).isRetryable()).isFalse();
        assertThat(parser.parse("""
                {"detail":[{"type":"missing","loc":["body","route_id"]}]}
                """, 422).isRetryable()).isFalse();
    }

    @Test
    @DisplayName("every real validation code from the probe round-trips")
    void parsesEveryObservedDomainCode() {
        String[] codes = {
                "IDEMPOTENCY_KEY_MISMATCH", "ROUTE_MISMATCH", "DEPOT_CLOSED", "STATION_CLOSED",
                "ROUTE_DISRUPTED", "ROUTE_CAPACITY_EXCEEDED", "INSUFFICIENT_INVENTORY",
                "DISPATCH_CAPACITY_EXCEEDED", "DESTINATION_CAPACITY_EXCEEDED", "CANNOT_CANCEL"
        };

        for (String code : codes) {
            String body = "{\"detail\":{\"code\":\"" + code + "\",\"message\":\"m\"}}";
            SimulatorError error = parser.parse(body, 409);

            assertThat(error)
                    .as("code %s", code)
                    .isInstanceOf(SimulatorError.Domain.class);
            assertThat(((SimulatorError.Domain) error).code()).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("ALLOCATION_NOT_FOUND carries no message field and still parses")
    void parsesAllocationNotFound() {
        String body = "{\"detail\":{\"code\":\"ALLOCATION_NOT_FOUND\"}}";

        SimulatorError error = parser.parse(body, 404);

        assertThat(((SimulatorError.Domain) error).code()).isEqualTo("ALLOCATION_NOT_FOUND");
        assertThat(((SimulatorError.Domain) error).message()).isNotBlank();
    }
}
