# rate-limiter

Token-bucket rate limiter service on Spring Boot 3 + embedded Infinispan.

```bash
mvn clean verify          # generates the API from src/main/resources/openapi, compiles, runs tests
mvn spring-boot:run

curl -s -X POST localhost:8080/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"orders-service:/v1/export","tokens":1}'
# -> 200 {"allowed":true,"limit":100,"remaining":99,"retryAfterMillis":0}
# -> 429 {"allowed":false,"limit":100,"remaining":0,"retryAfterMillis":10}
```

Generated sources land in `target/generated-sources/openapi` (`RateLimitApi`, `RateLimitApiController`,
`RateLimitApiDelegate`, models). Only `RateLimitApiDelegateImpl` is hand-written.

Run a second instance (`--server.port=8081`) on the same host and the two nodes form an Infinispan cluster;
buckets are distributed with 2 owners, so both instances enforce one shared limit per key.
