# Advanced Logging Patterns — Spring Boot 4 demo

A runnable version of *"Advanced Logging Patterns in Spring Boot Microservices: From Chaos to Clarity"*.
It recreates the article's 2 AM incident, "money debited, order not created", so the whole story
can be read by filtering on one `requestId`.

## Article → code map

| Article section | Where |
|---|---|
| Log levels (TRACE…ERROR) | `order/PaymentService`, `NotificationService`, `OrderService`, `inventory/InventoryController` |
| Correlation ID filter | `logging/CorrelationIdFilter`: reuses/validates `X-Request-Id`, echoes it, cleans up only its own MDC keys |
| Propagating IDs across services | `logging/CorrelationIdPropagatingInterceptor` + `order/InventoryClient` (real HTTP hop) |
| MDC "backpack" | `requestId`, `userId`, `orderId` (`MDC.putCloseable`), `paymentId`: see `logging/MdcKeys` |
| Enterprise log pattern | `logback-spring.xml` → `HUMAN_PATTERN` (profile `local`, the default) |
| Structured JSON logging | `logback-spring.xml` → `LogstashEncoder` (console in any non-`local` profile, always in `logs/app.json`) |
| Exception logging | Exception passed as the **last** argument everywhere; `web/GlobalExceptionHandler` returns the `requestId` |
| Distributed tracing | `spring-boot-starter-opentelemetry` → `traceId`/`spanId` in MDC, W3C `traceparent` propagation, optional Jaeger export |
| ELK stack | `docker-compose.yml`, `ops/filebeat`, `ops/logstash` |
| Rule 1: never log secrets | `OrderRequest#toString` masks the card; `MaskingJsonGeneratorDecorator` in logback is the safety net |
| How senior engineers read logs | `GET /api/logs/timeline/{requestId}` → chronological events + `rootCause` (first ERROR) |
| DSA log analyzer | `analyzer/LogAnalyzer`: HashMap counting + top-K errors (`GET /api/logs/errors/top?k=5`) |

## Run it

```bash
./gradlew bootRun                                          # readable logs, port 8085
./gradlew bootRun --args='--spring.profiles.active=prod'   # JSON logs

# Happy path
curl -i -X POST localhost:8085/api/orders -H 'Content-Type: application/json' \
  -H 'X-Request-Id: REQ-123' -H 'X-User-Id: USR-11' \
  -d '{"productId":"SKU-LAPTOP","quantity":1,"amount":49.99,"cardNumber":"4111111111111111"}'

# The 2 AM incident: payment succeeds, inventory fails, rollback
curl -i -X POST localhost:8085/api/orders -H 'Content-Type: application/json' \
  -H 'X-Request-Id: REQ-456' -H 'X-User-Id: USR-11' \
  -d '{"productId":"SKU-RARE","quantity":1,"amount":49.99,"cardNumber":"4111111111111111"}'

curl localhost:8085/api/logs/timeline/REQ-456   # the story + root cause
curl localhost:8085/api/logs/errors/top?k=3     # most frequent error signatures

# Change a log level at runtime, no restart
curl -X POST localhost:8085/actuator/loggers/com.company.orders -H 'Content-Type: application/json' -d '{"configuredLevel":"TRACE"}'
```

Stock: `SKU-LAPTOP`=10, `SKU-PHONE`=4 (triggers the low-stock WARN), `SKU-RARE`=0 (always fails).

## ELK + Jaeger

```bash
docker compose up -d
TRACING_EXPORT_ENABLED=true ./gradlew bootRun
```

- Kibana dashboard: http://localhost:5601/app/dashboards#/view/errors-by-request (imported automatically)
  - **Errors by requestId** table → click a requestId to filter every panel to that request
  - **Request timeline** → that request's full story across services, all levels, in order
  - **traceId** cells link straight to the Jaeger trace
  - Edit `ops/kibana/build_dashboard.py`, re-run it, then `docker compose up -d kibana-setup` to re-import
- Jaeger: http://localhost:16686 → service `order-service`

## Gotchas this project hit (and fixed)

- **`MDC.clear()` in the filter** (as the article shows) also wipes tracing's `traceId`/`spanId`. Remove only your own keys.
- **`management.tracing.export.enabled=false` in Boot 4 installs a no-op propagator**, so each HTTP hop gets a
  new traceId. To stop *sending* spans, disable only the exporter: `management.tracing.export.otlp.enabled=false`.
- **Records print every field in `toString()`**, so `log.info("{}", request)` would leak a card number. Override it.
- An incoming `X-Request-Id` is user input. Validate it, or someone can inject fake log lines.

## Top-K errors: min-heap

`LogAnalyzer#topErrors` keeps a min-heap capped at k: O(n log k) time and O(k) memory. The article's version
puts all n entries in a max-heap (O(n log n), O(n)). With 100k distinct error signatures and k=5, the heap
never grows past 6 entries.
