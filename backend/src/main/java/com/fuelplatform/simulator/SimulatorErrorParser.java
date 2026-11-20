package com.fuelplatform.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a simulator error body into a typed {@link SimulatorError}.
 *
 * <p>The discrimination is deliberately explicit rather than annotation-driven,
 * because the same field name carries two different JSON types. See
 * {@link SimulatorError} for the three observed shapes.
 */
public final class SimulatorErrorParser {

    private final ObjectMapper objectMapper;

    public SimulatorErrorParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param body raw response body, may be null or blank
     * @param httpStatus the status code that came with it
     */
    public SimulatorError parse(String body, int httpStatus) {
        if (body == null || body.isBlank()) {
            return new SimulatorError.Unparseable("empty error body", httpStatus);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            return new SimulatorError.Unparseable("error body is not JSON: " + body, httpStatus);
        }

        // Shape 3: injected fault, top-level "error".
        JsonNode errorNode = root.get("error");
        if (errorNode != null && errorNode.isObject()) {
            String code = text(errorNode, "code");
            String message = text(errorNode, "message");
            return new SimulatorError.Fault(
                    code == null ? "FAULT_INJECTED" : code,
                    message == null ? "simulator reported a fault" : message,
                    httpStatus);
        }

        JsonNode detailNode = root.get("detail");
        if (detailNode == null) {
            return new SimulatorError.Unparseable("no 'error' or 'detail' field in error body", httpStatus);
        }

        // Shape 2: Pydantic validation, "detail" is an ARRAY.
        if (detailNode.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : detailNode) {
                String type = text(item, "type");
                String loc = location(item);
                String msg = text(item, "msg");
                StringBuilder sb = new StringBuilder();
                if (loc != null) {
                    sb.append(loc).append(": ");
                }
                if (msg != null) {
                    sb.append(msg);
                }
                if (type != null) {
                    sb.append(" (").append(type).append(")");
                }
                parts.add(sb.toString());
            }
            String summary = parts.isEmpty() ? "request failed schema validation" : String.join("; ", parts);
            return new SimulatorError.Validation(summary, httpStatus);
        }

        // Shape 1: domain error, "detail" is an OBJECT.
        if (detailNode.isObject()) {
            String code = text(detailNode, "code");
            String message = text(detailNode, "message");
            return new SimulatorError.Domain(
                    code == null ? "UNKNOWN" : code,
                    message == null ? "simulator rejected the request" : message,
                    httpStatus);
        }

        return new SimulatorError.Unparseable("unexpected shape of 'detail'", httpStatus);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Renders a Pydantic {@code loc} array as {@code body.field[0]}. */
    private static String location(JsonNode item) {
        JsonNode loc = item.get("loc");
        if (loc == null || !loc.isArray() || loc.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : loc) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(part.asText());
        }
        return sb.toString();
    }
}
