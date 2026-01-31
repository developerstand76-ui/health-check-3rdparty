# Third-Party API Health Check Service

## Project Overview
Spring Boot service for monitoring third-party API endpoints with comprehensive edge case handling.

## Completed Setup

- [x] Spring Boot 3.2.1 with Java 17
- [x] Maven build system
- [x] Health check service with circuit breaker pattern
- [x] Retry logic with exponential backoff
- [x] Response caching (15s TTL)
- [x] Scheduled monitoring every 30 seconds
- [x] Swagger UI documentation
- [x] Comprehensive test coverage

## Key Components

**Models:**
- Target - API endpoint configuration
- HealthCheckResult - Check results with diagnostics
- HealthStatus - UP, DOWN, DEGRADED, UNKNOWN
- ErrorCategory - TIMEOUT, DNS_FAILURE, TLS_ERROR, etc.

**Services:**
- HealthCheckService - Core business logic
- HealthCheckProperties - Configuration management
- CircuitBreakerState - Failure tracking

**Transport:**
- HttpTransport (interface)
- JavaHttpTransport - Java 17 HttpClient implementation
- FakeTransport - Test implementation

**Controllers:**
- HealthCheckController - REST API endpoints

## API Endpoints
- POST /api/targets - Create health check target
- GET /api/targets - List all targets
- GET /api/targets/{id} - Get specific target
- PUT /api/targets/{id} - Update target
- DELETE /api/targets/{id} - Delete target
- POST /api/targets/{id}/check - Trigger manual check
- GET /api/health/results - Get all results
- GET /api/health/summary - Get status summary

## Edge Cases Covered
All 10 edge case categories implemented with proper error handling, retry logic, and circuit breaker protection. See README.md for detailed test data.

## Running the Application

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run

# Test
mvn test

# Swagger UI
http://localhost:8080/swagger-ui.html
```

## Development Guidelines
- Follow Spring Boot best practices
- Use dependency injection via constructor
- Write unit tests for all edge cases
- Keep business logic in service layer
- Use DTOs for API contracts
- Document endpoints with Swagger annotations
