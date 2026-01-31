# Third-Party API Health Check Service

Spring Boot service that monitors external API endpoints with comprehensive edge case handling and web-based request/response testing.

## Requirements
- Java 17+
- Maven 3.6+

## Quick Start

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run

# Test
mvn test
```

**Swagger UI:** http://localhost:8080/swagger-ui.html

## Core Features

### Health Monitoring
- **Circuit breaker pattern** - Opens after 3 consecutive failures
- **Retry logic** - Exponential backoff (200ms base delay)
- **Response caching** - 15 second TTL to reduce load
- **Scheduled checks** - Automatic monitoring every 30 seconds
- **Response time tracking** - Latency measurement in milliseconds

### Web Request Support
- All HTTP methods: GET, POST, PUT, DELETE, PATCH
- Custom headers for authentication/authorization
- Request bodies with configurable content types
- Response body capture (2048 character preview)
- Response headers extraction for debugging

### Status Classification
- `UP` - Healthy (2xx response, within performance thresholds)
- `DOWN` - Failing (errors, timeouts, unexpected status codes)
- `DEGRADED` - Slow but functional (exceeds slowThreshold)
- `UNKNOWN` - Initial/unchecked state

## Architecture

```
┌─────────────────────────┐
│  HealthCheckController  │  REST API Layer
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│  HealthCheckService     │  Business Logic
│  - Circuit Breaker      │  - Retry Logic
│  - Cache Management     │  - Scheduling
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│  JavaHttpTransport      │  HTTP Execution
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│  Java 17 HttpClient     │  Network Layer
└─────────────────────────┘
```

### Key Components
- **Target** - API endpoint configuration (URL, method, headers, body, thresholds)
- **HealthCheckResult** - Check results with diagnostics (status, latency, errors, response data)
- **CircuitBreakerState** - Failure tracking and recovery
- **HealthCheckProperties** - Externalized configuration

## Logical Flow Overview

### Request Flow - Create Target
```
1. User submits JSON with target config (name, url, method, timeout, etc.)
   ↓
2. Controller validates with @Valid annotation
   ├─ URL format validation ✓
   ├─ NotBlank checks ✓
   ├─ Duration parsing (ISO 8601: PT3S format) ✓
   └─ If invalid → 400 Bad Request
   ↓
3. Service creates Target object
   ├─ Generates UUID ID
   ├─ Auto-sets contentType to "application/json" if body exists
   └─ Stores in ConcurrentHashMap
   ↓
4. Service initializes CircuitBreakerState for this target
   ├─ consecutiveFailures = 0
   └─ openUntil = null
   ↓
5. Returns 201 Created with Target object
```

### Health Check Flow
```
1. Check if target exists → 404 if not found
   ↓
2. Determine cache strategy
   ├─ force=true → Always bypass cache
   └─ force=false → Check cache validity
      ├─ If cached result < 15s old → Return cached (fromCache=true)
      └─ If cached result > 15s old → Continue to check
   ↓
3. Check Circuit Breaker state
   ├─ If OPEN (3+ failures) → Return DOWN immediately (fail-fast)
   └─ If CLOSED → Continue to check
   ↓
4. Retry Loop (max attempts = maxRetries + 1)
   ├─ Execute check, get result
   ├─ If UP → Return immediately ✓
   ├─ If non-retryable error → Return immediately ✗
   └─ If retryable error → Sleep & retry
      └─ Sleep delay = baseBackoff × 2^attempt (exponential)
   ↓
5. Store result in cache (15s TTL)
   ↓
6. Update Circuit Breaker
   ├─ If UP → Reset (failures=0, openUntil=null)
   └─ If DOWN → Increment failures
      └─ If failures >= 3 → Open circuit (30s window)
```

### Single Execution Classification
```
HTTP Response Classification (in order):
├─ Latency > slowThreshold → DEGRADED
├─ Status 401/403 → DOWN (AUTH_FAILURE)
├─ Status 429 → DOWN (RATE_LIMIT)
├─ Unexpected status code → DOWN (HTTP_ERROR)
├─ Missing expected body content → DOWN (HTTP_ERROR)
├─ Invalid JSON (if expected) → DOWN (INVALID_JSON)
└─ All checks pass → UP ✓

Exception Classification:
├─ HttpTimeoutException → DOWN (TIMEOUT, retryable)
├─ UnknownHostException → DOWN (DNS_FAILURE, non-retryable)
├─ SSLHandshakeException → DOWN (TLS_ERROR, non-retryable)
├─ ConnectException → DOWN (CONNECTION_FAILURE, retryable)
└─ Other exceptions → DOWN (UNKNOWN)
```

### Scheduled Background Checks
```
Every 30 seconds automatically:
├─ Loop through all stored targets
├─ For each target: Call checkTarget(id, force=false)
├─ Uses cache if available (≤15s)
├─ Results stored in lastResults map
└─ Circuit breaker state updated
```

### Design Patterns Used
| Pattern | Implementation | Purpose |
|---------|---|---|
| **Circuit Breaker** | CircuitBreakerState tracks consecutive failures | Fail fast after 3 failures for 30s |
| **Retry with Backoff** | Exponential backoff (200ms × 2^attempt) | Handle transient network errors |
| **Caching** | 15s TTL checked by timestamp | Reduce load on external APIs |
| **Scheduling** | @Scheduled annotation (30s interval) | Continuous monitoring without intervention |
| **Thread-Safe** | ConcurrentHashMap for all storage | Safe concurrent access from scheduler + REST |

### Retryable vs Non-Retryable Errors
**Retryable** (will retry with exponential backoff):
- TIMEOUT
- CONNECTION_FAILURE  
- RATE_LIMIT (429)
- HTTP_ERROR (5xx, 408)

**Non-Retryable** (fail immediately):
- DNS_FAILURE
- TLS_ERROR
- AUTH_FAILURE (401, 403)
- INVALID_JSON
- CIRCUIT_OPEN

## Edge Cases Handled

| Category | Detection | Behavior | Example Scenario |
|----------|-----------|----------|------------------|
| `TIMEOUT` | HttpTimeoutException | Retries with backoff | API takes >3s to respond |
| `DNS_FAILURE` | UnknownHostException | Fails immediately | Invalid hostname |
| `TLS_ERROR` | SSLHandshakeException | Fails immediately | Expired SSL certificate |
| `CONNECTION_FAILURE` | ConnectException | Retries with backoff | Port closed/firewall |
| `AUTH_FAILURE` | HTTP 401/403 | Fails immediately | Invalid API key |
| `RATE_LIMIT` | HTTP 429 | Retries with backoff | Too many requests |
| `HTTP_ERROR` | HTTP 5xx/408 | Retries with backoff | Server errors |
| `INVALID_JSON` | JSON parse failure | Fails immediately | Expected JSON, got HTML |
| `SLOW_RESPONSE` | Latency check | Marks as DEGRADED | Response >2s but <3s |
| `CIRCUIT_OPEN` | Breaker state | Fails fast | 3+ failures within window |

## API Endpoints

### Target Management

**Create Target**
```
POST /api/targets
Content-Type: application/json
```

**List All Targets**
```
GET /api/targets
```

**Get Target by ID**
```
GET /api/targets/{id}
```

**Update Target**
```
PUT /api/targets/{id}
Content-Type: application/json
```

**Delete Target**
```
DELETE /api/targets/{id}
```

### Health Check Operations

**Trigger Manual Check**
```
POST /api/targets/{id}/check?force=true
```
- `force=true` - Bypass cache, perform fresh check
- `force=false` - Use cached result if available

**Get All Results**
```
GET /api/health/results
```

**Get Status Summary**
```
GET /api/health/summary
```
Returns count by status: `{"UP": 5, "DOWN": 2, "DEGRADED": 1}`

## Test Data for Swagger UI

**Important:** All duration fields (timeout, slowThreshold) must use ISO 8601 format:
- `PT3S` = 3 seconds
- `PT2S` = 2 seconds  
- `PT1S` = 1 second
- `PT5S` = 5 seconds

Copy-paste the examples below into Swagger UI or use the correct ISO format in direct API calls.

### 1. Healthy API (UP)
```json
{
  "name": "Healthy API",
  "url": "https://httpbin.org/status/200",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT2S",
  "maxRetries": 2
}
```

### 2. Timeout Test (TIMEOUT)
```json
{
  "name": "Timeout API",
  "url": "https://httpbin.org/delay/5",
  "method": "GET",
  "timeout": "PT2S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT1S",
  "maxRetries": 1
}
```

### 3. DNS Failure (DNS_FAILURE)
```json
{
  "name": "DNS Failure",
  "url": "https://this-domain-does-not-exist-12345.com/api",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT2S",
  "maxRetries": 1
}
```

### 4. HTTP Error (HTTP_ERROR)
```json
{
  "name": "Server Error API",
  "url": "https://httpbin.org/status/500",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT2S",
  "maxRetries": 2
}
```

### 5. Auth Failure (AUTH_FAILURE)
```json
{
  "name": "Auth Required API",
  "url": "https://httpbin.org/status/401",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT2S",
  "maxRetries": 1
}
```

### 6. Rate Limit (RATE_LIMIT)
```json
{
  "name": "Rate Limited API",
  "url": "https://httpbin.org/status/429",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT2S",
  "maxRetries": 2
}
```

### 7. Invalid JSON (INVALID_JSON)
```json
{
  "name": "Invalid JSON API",
  "url": "https://httpbin.org/html",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "expectJson": true,
  "slowThreshold": "PT2S",
  "maxRetries": 1
}
```

### 8. Slow Response (DEGRADED)
```json
{
  "name": "Slow API",
  "url": "https://httpbin.org/delay/2",
  "method": "GET",
  "timeout": "PT5S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT1S",
  "maxRetries": 1
}
```

### 9. POST with Request Body
```json
{
  "name": "POST Echo API",
  "url": "https://httpbin.org/post",
  "method": "POST",
  "requestBody": "{\"test\": \"data\", \"timestamp\": 1234567890}",
  "contentType": "application/json",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "expectJson": true,
  "slowThreshold": "PT2S",
  "maxRetries": 1
}
```

### 10. Response Body Validation
```json
{
  "name": "Body Content Check",
  "url": "https://httpbin.org/json",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "expectJson": true,
  "expectedBodyContains": "slideshow",
  "slowThreshold": "PT2S",
  "maxRetries": 1
}
```

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# Server
server.port=8080

# Health Check Settings
healthcheck.cache-ttl=15s                      # Cache validity duration
healthcheck.circuit-failure-threshold=3        # Failures before circuit opens
healthcheck.circuit-open-duration=30s          # How long circuit stays open
healthcheck.retry-base-backoff=200ms           # Initial retry delay
healthcheck.scheduler-delay=30000              # Scheduled check interval (ms)
healthcheck.max-response-body-chars=2048       # Response body preview limit
```

## Testing Strategy

### Unit Tests
- **HealthCheckServiceTest** - 12 test cases covering all edge cases
- **HealthCheckControllerTest** - Controller layer validation
- **FakeTransport** - Controlled test environment

### Test Execution
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=HealthCheckServiceTest

# Skip tests during build
mvn clean package -DskipTests
```

## Scheduled Monitoring

The service automatically checks all configured targets every 30 seconds. Results are cached and accessible via:
- `/api/health/results` - Detailed results for all targets
- `/api/health/summary` - Aggregated status counts

To disable scheduling, remove `@EnableScheduling` from `Application.java`.

## Response Data Structure

```json
{
  "id": "uuid",
  "status": "UP|DOWN|DEGRADED|UNKNOWN",
  "httpStatus": 200,
  "latencyMs": 145,
  "responseBodyPreview": "{\"status\":\"ok\"}",
  "responseHeaders": {
    "Content-Type": "application/json",
    "Server": "nginx"
  },
  "errorCategory": "NONE",
  "errorMessage": null,
  "timestamp": "2026-01-30T13:21:00Z",
  "attempts": 1,
  "fromCache": false,
  "cachedAt": null
}
```

## Troubleshooting

### Circuit Breaker Opens Too Quickly
Increase `healthcheck.circuit-failure-threshold` in properties.

### False Timeouts
Increase `timeout` value when creating targets or adjust `healthcheck.retry-base-backoff`.

### High Memory Usage
Reduce `healthcheck.cache-ttl` or `healthcheck.max-response-body-chars`.

### Tests Failing
Some tests use real network calls to httpbin.org. Ensure internet connectivity or adjust test implementation.

## License

MIT License
