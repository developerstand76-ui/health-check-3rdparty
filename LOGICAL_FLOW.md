# Logical Flow Overview - Third-Party API Health Check Service

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Spring Boot Application                      │
│                   @EnableScheduling enabled                      │
└─────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ REST Controller  │  │   Background     │  │   In-Memory      │
│                  │  │   Scheduler      │  │   Data Store     │
│ (User Requests)  │  │ (Periodic Checks)│  │ (Thread-Safe)    │
└────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
         │                     │                     │
         └─────────────────────┼─────────────────────┘
                               ▼
                    ┌──────────────────────────┐
                    │ HealthCheckService       │
                    │ (Core Business Logic)    │
                    └────────┬─────────────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
            ▼                ▼                ▼
    ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
    │ Caching      │ │ Circuit      │ │ Retry Logic  │
    │ (15s TTL)    │ │ Breaker      │ │ w/ Backoff   │
    │              │ │ (Fail fast)  │ │ (Exponential)│
    └──────────────┘ └──────────────┘ └──────────────┘
            │                │                │
            └────────────────┼────────────────┘
                             ▼
                  ┌──────────────────────────┐
                  │  HTTP Transport Layer    │
                  │ (Java 17 HttpClient)     │
                  └────────┬─────────────────┘
                           ▼
                  ┌──────────────────────────┐
                  │   Third-Party APIs       │
                  │   (External Endpoints)   │
                  └──────────────────────────┘
```

---

## Request Flow - POST /api/targets (Create Target)

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

---

## Health Check Flow - POST /api/targets/{id}/check (Manual Check)

```
1. Check if target exists
   └─ If not → 404 Not Found
   ↓
2. Determine if cache should be used
   ├─ force=true → Always bypass cache
   └─ force=false → Check cache validity
      ├─ If cached result < 15s old → Return cached (fromCache=true)
      └─ If cached result > 15s old → Continue
   ↓
3. Check Circuit Breaker state
   ├─ If OPEN (3+ failures in window) → Return DOWN immediately (fail-fast)
   └─ If CLOSED → Continue to check
   ↓
4. Retry Loop (max attempts = maxRetries + 1)
   ├─ Iteration 1:
   │  └─ Execute check, get result
   │  └─ If UP → Return immediately ✓
   │  └─ If non-retryable error → Return immediately ✗
   │  └─ If retryable error → Sleep & retry
   │
   ├─ Iteration 2+:
   │  └─ Same as iteration 1
   │  └─ Sleep delay = baseBackoff × 2^attempt (exponential)
   │  └─ Example: 200ms → 400ms → 800ms...
   ↓
5. Store result in cache
   ├─ lastResults.put(targetId, result)
   └─ Timestamp = Instant.now()
   ↓
6. Update Circuit Breaker
   ├─ If UP → Reset (failures=0, openUntil=null)
   └─ If DOWN → Increment failures
      └─ If failures >= 3 → Open circuit (30s window)
```

---

## Single Execution - executeOnce(target)

```
1. Start timer (latencyMs measurement)
   ↓
2. Execute HTTP request via transport layer
   └─ Includes: method, URL, headers, body, timeout
   ↓
3. Classify Response (in order):
   
   ├─ RESPONSE RECEIVED:
   │  ├─ Latency check → If > slowThreshold → DEGRADED (slow)
   │  ├─ Auth codes (401, 403) → DOWN (AUTH_FAILURE)
   │  ├─ Rate limit (429) → DOWN (RATE_LIMIT)
   │  ├─ Wrong status code → DOWN (HTTP_ERROR)
   │  ├─ Missing body content → DOWN (HTTP_ERROR)
   │  ├─ Invalid JSON (if expected) → DOWN (INVALID_JSON)
   │  └─ All checks pass → UP ✓
   │
   ├─ TIMEOUT → DOWN (TIMEOUT, retryable)
   ├─ DNS failure → DOWN (DNS_FAILURE, retryable)
   ├─ TLS error → DOWN (TLS_ERROR, retryable)
   ├─ Connection refused → DOWN (CONNECTION_FAILURE, retryable)
   └─ Other exceptions → DOWN (UNKNOWN)
   ↓
4. Return HealthCheckResult with:
   ├─ Status (UP, DOWN, DEGRADED, UNKNOWN)
   ├─ HTTP status code
   ├─ Latency in ms
   ├─ Response preview (2048 char limit)
   ├─ Response headers
   ├─ Error category
   ├─ Error message
   ├─ Timestamp
   └─ Attempt number
```

---

## Scheduled Background Checks - @Scheduled (Every 30s)

```
Every 30 seconds automatically:
   ↓
1. Loop through all stored targets
   ↓
2. For each target:
   └─ Call checkTarget(id, force=false)
   └─ Uses cache if available (≤15s)
   ├─ First check → No cache → Executes check
   ├─ Second check (30s later) → Cache valid if <15s → Still executed
   └─ Continues until cache expires
   ↓
3. Results stored in lastResults map
   ↓
4. Circuit breaker state updated
```

---

## GET /api/health/summary (Status Overview)

```
Group lastResults by HealthStatus → Count occurrences
   ↓
Return:
{
  "statusCounts": {
    "UP": 5,
    "DOWN": 2,
    "DEGRADED": 1,
    "UNKNOWN": 0
  },
  "lastUpdated": "2026-01-31T15:24:43.321792700Z"
}
```

---

## Key Design Patterns

| Pattern | Implementation | Purpose |
|---------|---|---|
| **Circuit Breaker** | `CircuitBreakerState` - tracks consecutive failures | Prevents cascading failures; fails fast after 3 failures for 30s |
| **Retry with Backoff** | `attemptWithRetries()` + `sleepBackoff()` | Handles transient errors (timeouts, connection issues) |
| **Caching** | 15s TTL checked by timestamp | Reduces load on external APIs |
| **Scheduling** | `@Scheduled` annotation | Continuous monitoring without manual intervention |
| **Dependency Injection** | Constructor injection in Service | Testability; easy to mock transport layer |
| **Thread-Safe Storage** | `ConcurrentHashMap` for all maps | Safe for concurrent scheduler + REST controller |

---

## Error Classification & Retry Logic

### Retryable Errors (will retry with exponential backoff)
- **TIMEOUT** - Request exceeded timeout duration
- **CONNECTION_FAILURE** - Unable to establish connection
- **RATE_LIMIT** (429) - Server rate limiting
- **HTTP_ERROR** (5xx, 408) - Server errors or request timeout status

### Non-Retryable Errors (fail immediately)
- **DNS_FAILURE** - Invalid hostname/DNS resolution failed
- **TLS_ERROR** - SSL/TLS handshake failed
- **AUTH_FAILURE** (401, 403) - Authentication/authorization failed
- **INVALID_JSON** - Response is not valid JSON when expected
- **CIRCUIT_OPEN** - Circuit breaker is open

---

## Data Flow Example - Complete Scenario

### Step 1: Create a Target
```bash
POST /api/targets
{
  "name": "API Server",
  "url": "https://api.example.com/health",
  "method": "GET",
  "timeout": "PT3S",
  "expectedStatusMin": 200,
  "expectedStatusMax": 299,
  "slowThreshold": "PT2S",
  "maxRetries": 2
}
```
**Result:** Target created with ID = `550e8400-e29b-41d4-a716-446655440000`

---

### Step 2: Scheduled Check Runs (30s later)
```
Time: 00:00
  └─ Scheduler triggers
  └─ Calls checkTarget(550e8400..., force=false)
  └─ No cache exists yet
  └─ Circuit breaker is CLOSED
  └─ Starts retry loop

Attempt 1 (Time: 00:00)
  └─ Execute HTTP GET to https://api.example.com/health
  └─ Receives: status=200, latency=150ms, body={...}
  └─ Latency 150ms < slowThreshold 2000ms ✓
  └─ Status 200 in range [200, 299] ✓
  └─ Result: UP
  └─ Return immediately (success, no retry)

Store Result:
  ├─ lastResults[550e8400...] = HealthCheckResult(UP, 150ms)
  ├─ Circuit breaker: recordSuccess() → failures=0, openUntil=null
  └─ Cache expires at: 00:15 (15 seconds from now)
```

---

### Step 3: Manual Check Within Cache Window (5s later)
```
Time: 00:05
  └─ User calls: POST /api/targets/550e8400.../check?force=false
  └─ Check cache: Age = 5s < TTL 15s ✓
  └─ Return cached result with fromCache=true, cachedAt=00:00
```

---

### Step 4: Three Consecutive Failures Scenario
```
Time: 01:00 - Connection timeout
  └─ Attempt 1: Timeout exception → DOWN, TIMEOUT (retryable)
  └─ Sleep: 200ms (baseBackoff)
  └─ Attempt 2: Timeout exception → DOWN, TIMEOUT (retryable)
  └─ Sleep: 400ms (200 × 2^1)
  └─ Attempt 3: Timeout exception → DOWN, TIMEOUT (retryable)
  └─ No more retries (maxRetries=2, so 3 attempts max)
  └─ Return: DOWN
  └─ Circuit breaker: recordFailure() → failures=1, openUntil=null

Time: 01:30 - Another failure
  └─ Attempt 1: Connection refused → DOWN (retryable)
  └─ [Retry logic...]
  └─ Circuit breaker: recordFailure() → failures=2, openUntil=null

Time: 02:00 - Third failure (circuit opens!)
  └─ Attempt 1: Timeout → DOWN (retryable)
  └─ [Retry logic...]
  └─ Circuit breaker: recordFailure() → failures=3 >= threshold 3
  └─ openUntil = NOW + 30s = 02:30

Time: 02:15 - Circuit breaker is OPEN
  └─ Call checkTarget(550e8400..., force=false)
  └─ Check circuit: isOpen() = true
  └─ Return immediately: DOWN (CIRCUIT_OPEN)
  └─ No HTTP request made (fast-fail)

Time: 02:31 - Circuit automatically closes
  └─ Now >= openUntil
  └─ Circuit breaker: isOpen() = false
  └─ Next check will attempt HTTP request again
```

---

## Configuration & Tuning

### Default Settings (application.properties)
```properties
healthcheck.cache-ttl=15s                      # Cache validity period
healthcheck.circuit-failure-threshold=3        # Failures before circuit opens
healthcheck.circuit-open-duration=30s          # Circuit stay-open window
healthcheck.retry-base-backoff=200ms           # Initial retry delay
healthcheck.scheduler-delay=30000              # Background check interval (ms)
healthcheck.max-response-body-chars=2048       # Response body preview limit
```

### How to Tune for Different Scenarios

**Scenario: Many transient errors (network blips)**
- Increase `maxRetries` to 3-4 in target config
- Decrease `retry-base-backoff` to 100ms

**Scenario: Want to avoid cascading failures**
- Decrease `circuit-failure-threshold` to 2
- Keep `circuit-open-duration` at 30-60s

**Scenario: High-frequency API that doesn't change often**
- Increase `cache-ttl` to 30-60s
- Decrease `scheduler-delay` to 10000ms (10s)

**Scenario: API with known slow responses**
- Increase `timeout` in target config
- Increase `slowThreshold` in target config

---

## Threading & Concurrency

The service uses `ConcurrentHashMap` for thread-safe access:

```
┌─────────────────────────────────────────┐
│ Thread 1: REST Controller               │
│ (Handles POST /api/targets/{id}/check)  │
│ Calls: checkTarget(id, force=true)      │
└─────────────────────────────────────────┘
         ↓ (concurrent access)
┌─────────────────────────────────────────┐
│ ConcurrentHashMap<UUID, Target>         │
│ ConcurrentHashMap<UUID, HealthResult>   │
│ ConcurrentHashMap<UUID, CircuitBreaker> │
└─────────────────────────────────────────┘
         ↑ (concurrent access)
┌─────────────────────────────────────────┐
│ Thread 2: Background Scheduler          │
│ (@Scheduled fixedDelay=30s)             │
│ Calls: checkTarget(id, force=false)     │
└─────────────────────────────────────────┘
```

No locking needed - both threads safely read/write to concurrent maps without blocking each other.

---

## Performance Characteristics

| Operation | Time | Notes |
|-----------|------|-------|
| Create target | <1ms | Just stores in memory |
| List targets | O(n) | Scans entire map |
| Manual check (cached) | <1ms | No network call |
| Manual check (fresh) | timeout duration | Depends on API response |
| Scheduled check | timeout duration | Runs in background thread |
| Summary aggregation | O(n) | Counts results |

---

## Failure Recovery Timeline

Example of circuit breaker recovery after 3 failures:

```
Time ──────────────────────────────────────────────────────────
      │
      ├─ 00:00 Failure 1 (failures=1, circuit CLOSED)
      │
      ├─ 01:00 Failure 2 (failures=2, circuit CLOSED)
      │
      ├─ 02:00 Failure 3 (failures=3, circuit OPENS, openUntil=02:30)
      │
      ├─ 02:15 [CIRCUIT OPEN - Fast Fail, no retries]
      │
      ├─ 02:30 Circuit auto-closes (failures remain=3, openUntil=null)
      │
      ├─ 02:31 Next check attempts recovery
      │        ├─ If successful → recordSuccess() → failures=0 ✓
      │        └─ If fails → recordFailure() → failures=4
      │           → Circuit opens again, openUntil=03:01
      │
      └─ 03:00 [Timeline repeats if failures continue]
```

---

## Common Use Cases

### Use Case 1: Monitor Multiple API Endpoints
```
Create 10 targets, each with different URLs
Scheduler automatically checks all every 30s
View aggregate status with GET /api/health/summary
```

### Use Case 2: Detect Performance Degradation
```
Set slowThreshold=PT1S in target config
Service marks as DEGRADED if latency > 1s
Use in monitoring dashboard to alert teams
```

### Use Case 3: Handle Rate-Limited APIs
```
API returns 429 when rate-limited
Service retries with exponential backoff
Circuit breaker prevents hammering rate-limited API
```

### Use Case 4: Authentication Monitoring
```
API returns 401 (invalid token)
Service fails immediately (non-retryable)
Team can see AUTH_FAILURE in results
Alert on credentials needing refresh
```
