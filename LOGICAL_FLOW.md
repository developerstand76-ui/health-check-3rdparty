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

## Why Circuit Breaker is Essential

### The Problem Without Circuit Breaker

```
Without Circuit Breaker:
Target API is DOWN for 30 minutes

Time: 00:00  POST /api/targets/abc/check
  └─ Attempt 1: Timeout (200ms backoff)
  └─ Attempt 2: Timeout (400ms backoff)
  └─ Attempt 3: Timeout (800ms backoff)
  └─ Total: ~1.4s to detect failure

Time: 00:30 (Scheduled check)
  └─ Attempt 1: Timeout ❌ [wasted network call]
  └─ Attempt 2: Timeout ❌ [wasted network call]
  └─ Attempt 3: Timeout ❌ [wasted network call]
  └─ Total: ~1.4s again

Time: 01:00 (Scheduled check)
  └─ Attempt 1-3: Timeouts ❌ [still wasting resources]

This repeats EVERY 30 seconds, hammering the broken API!
```

### The Solution With Circuit Breaker

**CircuitBreakerState tracks:**
```java
public class CircuitBreakerState {
    private int consecutiveFailures;  // Count of failures
    private Instant openUntil;         // When circuit auto-closes
}
```

**Protection Logic:**
```java
CircuitBreakerState breaker = circuitBreakers.computeIfAbsent(id, key -> new CircuitBreakerState());

if (breaker.isOpen()) {  // ← Check if open BEFORE making HTTP request
    // Return DOWN immediately without trying!
    result.setStatus(HealthStatus.DOWN);
    result.setErrorCategory(ErrorCategory.CIRCUIT_OPEN);
    return result;
}

// Only if closed, proceed with HTTP check...
HealthCheckResult result = attemptWithRetries(target);

// Update breaker based on result
if (result.getStatus() == HealthStatus.UP) {
    breaker.recordSuccess();
} else {
    breaker.recordFailure(3, 30000);  // Opens after 3 failures for 30s
}
```

### Timeline With Circuit Breaker

```
Time: 00:00 - Failure #1
  POST /api/targets/abc/check
  └─ Circuit: CLOSED → Attempt check
  └─ Result: DOWN (TIMEOUT)
  └─ recordFailure() → failures=1, circuit CLOSED
  
Time: 00:30 - Failure #2 (Scheduled)
  POST /api/targets/abc/check
  └─ Circuit: CLOSED → Attempt check
  └─ Result: DOWN (TIMEOUT)
  └─ recordFailure() → failures=2, circuit CLOSED

Time: 01:00 - Failure #3 (Scheduled)
  POST /api/targets/abc/check
  └─ Circuit: CLOSED → Attempt check
  └─ Result: DOWN (TIMEOUT)
  └─ recordFailure() → failures=3 >= threshold(3)
  └─ Circuit OPENS ✓ openUntil = 01:30

Time: 01:30 (Scheduled check)
  POST /api/targets/abc/check
  └─ breaker.isOpen() == true ✓
  └─ SKIP all retries! Return DOWN immediately (<1ms!)
  └─ No HTTP requests made! 💚

Time: 01:31 (User request)
  POST /api/targets/abc/check
  └─ breaker.isOpen() == true ✓
  └─ SKIP retries! Return DOWN immediately
  └─ No wasted network calls!

Time: 01:31 (Circuit auto-closes)
  └─ openUntil (01:30) < now (01:31)
  └─ breaker.isOpen() == false
  └─ Next check will attempt recovery
```

### Benefits

| Problem | Solution | Benefit |
|---------|----------|---------|
| **Hammering broken APIs** | Circuit opens after 3 failures | Stops wasting network bandwidth |
| **Cascading failures** | Fail fast instead of retrying | Protects downstream systems |
| **Resource waste** | Skip HTTP entirely when open | Reduces CPU, network, latency |
| **Recovery** | Auto-closes after 30s | Can detect when API recovers |

### Real-World Impact

**Without Circuit Breaker:**
- API goes down, service makes 1000s of wasted HTTP calls
- Increases load on broken API (might make it worse)
- Depletes connection pools
- Slow user responses waiting for timeouts

**With Circuit Breaker:**
- After 3 failures, returns DOWN instantly
- No more HTTP calls for 30 seconds
- Allows broken API time to recover
- Returns results in <1ms instead of timeout duration
- User gets fast feedback instead of hanging

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

---

## Test Examples Explained

### Example 1: Healthy API (UP)

**Configuration:**
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

**What Happens:**
```
1. Client sends GET to https://httpbin.org/status/200
2. httpbin.org responds immediately with HTTP 200 OK
3. Latency: ~100-300ms (well under slowThreshold of 2s)
4. Status code 200 is within expected range [200-299] ✓
```

**Result:** 
- **Status:** UP
- **Error Category:** NONE
- **Why:** All checks pass - fast response, correct status code

---

### Example 2: Timeout Test (TIMEOUT)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  Time: 0.0s → Send GET to https://httpbin.org/delay/5
  Time: 0.0s → Server starts 5-second delay
  Time: 2.0s → Client timeout reached! ❌
  └─ HttpTimeoutException thrown
  └─ Server still hasn't responded (still delaying)

Backoff: 200ms

Attempt 2:
  Time: 2.2s → Retry GET to https://httpbin.org/delay/5
  Time: 4.2s → Client timeout reached again! ❌
  └─ HttpTimeoutException thrown
  
Total time: ~4.4s (2s + 200ms + 2s)
```

**Result:**
- **Status:** DOWN
- **Error Category:** TIMEOUT
- **Why:** API delays 5 seconds, but client only waits 2 seconds. Client gives up before server responds.

---

### Example 3: DNS Failure (DNS_FAILURE)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  1. Client tries to resolve "this-domain-does-not-exist-12345.com"
  2. DNS lookup fails (domain doesn't exist)
  3. UnknownHostException thrown immediately ❌
  4. No HTTP request is even attempted

  └─ DNS_FAILURE is non-retryable
  └─ No retry attempts made
  
Total time: ~100ms (DNS lookup only)
```

**Result:**
- **Status:** DOWN
- **Error Category:** DNS_FAILURE
- **Why:** Domain doesn't exist in DNS, so connection cannot be established. Fails immediately without retry.

---

### Example 4: HTTP Error (HTTP_ERROR)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  └─ GET https://httpbin.org/status/500
  └─ Server responds with HTTP 500 Internal Server Error
  └─ Status code 500 NOT in expected range [200-299] ❌
  └─ 500 >= 500 → Retryable error

Backoff: 200ms

Attempt 2:
  └─ Retry GET
  └─ Server again responds with HTTP 500 ❌
  
Backoff: 400ms

Attempt 3:
  └─ Retry GET
  └─ Server again responds with HTTP 500 ❌
  
Total attempts: 3 (maxRetries=2 means 3 total attempts)
```

**Result:**
- **Status:** DOWN
- **Error Category:** HTTP_ERROR
- **Why:** Server returns 5xx error, which is retryable but keeps failing. Status code outside expected range.

---

### Example 5: Auth Failure (AUTH_FAILURE)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  └─ GET https://httpbin.org/status/401
  └─ Server responds with HTTP 401 Unauthorized
  └─ Special handling: response.getStatusCode() == 401 ❌
  └─ Classified as AUTH_FAILURE (non-retryable)
  └─ Return immediately, no retries

Total time: ~150ms (single request only)
```

**Result:**
- **Status:** DOWN
- **Error Category:** AUTH_FAILURE
- **Why:** 401/403 status codes indicate authentication problem. Retrying won't help (same credentials), so fails immediately.

---

### Example 6: Rate Limit (RATE_LIMIT)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  └─ GET https://httpbin.org/status/429
  └─ Server responds with HTTP 429 Too Many Requests
  └─ Special handling: response.getStatusCode() == 429 ❌
  └─ Classified as RATE_LIMIT (retryable with backoff)

Backoff: 200ms (gives server time to reset rate limit)

Attempt 2:
  └─ Retry GET
  └─ Server still responds with 429 ❌
  
Backoff: 400ms

Attempt 3:
  └─ Retry GET
  └─ Server still responds with 429 ❌
```

**Result:**
- **Status:** DOWN
- **Error Category:** RATE_LIMIT
- **Why:** Server is rate-limiting requests. Retries with exponential backoff to give server time to reset limits.

---

### Example 7: Invalid JSON (INVALID_JSON)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  1. GET https://httpbin.org/html
  2. Server responds with HTTP 200 OK ✓
  3. Response body: "<!DOCTYPE html><html>..." (HTML content)
  4. Check: expectJson == true
  5. Try: objectMapper.readTree(responseBody)
  6. Fails: JsonParseException (HTML is not valid JSON) ❌
  7. Classified as INVALID_JSON (non-retryable)
  
No retry (parsing won't suddenly succeed)
```

**Result:**
- **Status:** DOWN
- **Error Category:** INVALID_JSON
- **Why:** Expected JSON but got HTML. Status code is fine, but content type mismatch. Non-retryable.

---

### Example 8: Slow Response (DEGRADED)

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  Time: 0.0s → Send GET to https://httpbin.org/delay/2
  Time: 0.0s → Server starts 2-second delay
  Time: 2.0s → Server responds with HTTP 200 OK ✓
  
  Latency: 2000ms
  Check: latencyMs (2000) > slowThreshold (1000) ⚠️
  └─ Status code is OK (200 in [200-299]) ✓
  └─ But response is SLOW
  └─ Marked as DEGRADED (not UP, not DOWN)
```

**Result:**
- **Status:** DEGRADED
- **Error Category:** SLOW_RESPONSE
- **Why:** API responds successfully but too slowly (2s > 1s threshold). Functional but degraded performance.

---

### Example 9: POST with Request Body

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  1. Send POST to https://httpbin.org/post
     Headers: Content-Type: application/json
     Body: {"test": "data", "timestamp": 1234567890}
  
  2. httpbin.org/post echoes back the request data
     Response: HTTP 200 OK
     Body: {"json": {"test": "data", "timestamp": 1234567890}, ...}
  
  3. Latency: ~150ms (< slowThreshold 2s) ✓
  4. Status code: 200 (in [200-299]) ✓
  5. expectJson: true → Parse response as JSON ✓
  6. JSON parsing succeeds ✓
```

**Result:**
- **Status:** UP
- **Error Category:** NONE
- **Why:** POST request succeeds, response is valid JSON, latency is good, status code is expected.

---

### Example 10: Response Body Validation

**Configuration:**
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

**What Happens:**
```
Attempt 1:
  1. GET https://httpbin.org/json
  2. Server responds: HTTP 200 OK
     Body: {"slideshow": {"author": "Yours Truly", ...}}
  
  3. Latency: ~120ms (< slowThreshold 2s) ✓
  4. Status code: 200 (in [200-299]) ✓
  5. expectJson: true → Parse response ✓
  6. JSON parsing succeeds ✓
  7. expectedBodyContains: "slideshow"
     └─ Check: response.getBody().contains("slideshow") ✓
     └─ Found in response body ✓
```

**Result:**
- **Status:** UP
- **Error Category:** NONE
- **Why:** All validations pass - correct status, valid JSON, contains expected substring "slideshow".

**Alternative Scenario (if body didn't contain "slideshow"):**
```
Result would be:
  Status: DOWN
  Error Category: HTTP_ERROR
  Message: "Response body missing expected content"
```

---

## Summary of Test Examples

| # | Name | Trigger | Error Category | Retryable | Key Learning |
|---|------|---------|----------------|-----------|--------------|
| 1 | Healthy API | Normal 200 response | NONE | N/A | Baseline success case |
| 2 | Timeout | API delay > timeout | TIMEOUT | Yes | Client gives up before server responds |
| 3 | DNS Failure | Invalid hostname | DNS_FAILURE | No | Connection can't be established |
| 4 | HTTP Error | 5xx status code | HTTP_ERROR | Yes | Server-side errors get retried |
| 5 | Auth Failure | 401/403 status | AUTH_FAILURE | No | Credentials problem, retry won't help |
| 6 | Rate Limit | 429 status code | RATE_LIMIT | Yes | Backoff gives server time to reset |
| 7 | Invalid JSON | HTML when expecting JSON | INVALID_JSON | No | Content type mismatch |
| 8 | Slow Response | Latency > threshold | SLOW_RESPONSE | No | Functional but degraded |
| 9 | POST Body | POST with JSON body | NONE | N/A | Demonstrates request body handling |
| 10 | Body Validation | Check for substring | NONE | N/A | Validates response content |
