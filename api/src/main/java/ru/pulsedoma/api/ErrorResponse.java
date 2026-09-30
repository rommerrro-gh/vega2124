package ru.pulsedoma.api;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, Object> details, String correlationId) {}
