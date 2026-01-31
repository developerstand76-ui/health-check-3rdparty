package com.healthcheck.model;

public enum ErrorCategory {
    NONE,
    TIMEOUT,
    DNS_FAILURE,
    TLS_ERROR,
    CONNECTION_FAILURE,
    HTTP_ERROR,
    AUTH_FAILURE,
    RATE_LIMIT,
    INVALID_JSON,
    SLOW_RESPONSE,
    CIRCUIT_OPEN,
    UNKNOWN
}
