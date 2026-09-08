# rate-limiter

Token-bucket rate limiter service on Spring Boot 3 + embedded Infinispan.

```bash
mvn clean verify          # generates the API from src/main/resources/openapi, compiles, runs tests
mvn spring-boot:run

curl -s -X POST localhost:8051/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
# -> 200 {"allowed":true,"limit":15,"remaining":14,"retryAfterMillis":60000}
# -> 429 {"allowed":false,"limit":15,"remaining":0,"retryAfterMillis":53413}   + Retry-After header
```

## Buckets

A bucket is identified by **`<resource>@<identifier>`**, e.g. `subjectSearch@10.0.0.7`.

- **resource** - a fixed set of limited things (calls into the subject service, calls into our own
  service, ...). Declared as the `resource` enum in the OpenAPI spec and mirrored by
  `com.example.ratelimiter.core.Resource`. Each resource carries its own capacity and refill quota.
- **identifier** - freeform: an IP, a user id, an organisation id, a session id. A resource's policy
  applies to every identifier independently.

## Policies

Configured under `ratelimiter.resources` in `application.yml`, keyed by the resource's wire value:

```yaml
ratelimiter:
  resources:
    subjectSearch:
      capacity: 15          # max burst; a fresh bucket starts full
      refill-tokens: 6      # tokens granted per period
      refill-period: 60s
    maxCallsIp:
      capacity: 100000
      refill-tokens: 100000
      refill-period: 24h
```

Refill is **stepwise, not a continuous drip**: at each period boundary the bucket gains
`refill-tokens`, capped at `capacity`. Mid-period nothing accrues.

`retryAfterMillis` is always the wait until the next refill that helps, on allowed and refused calls
alike: when refused, until enough tokens are back; when allowed, until the next refill lands. It is
never 0 (the spec's "0 otherwise" wording is out of date), so a caller can pace itself before it is
refused. The `Retry-After` header is still sent on 429s only. Every value of `Resource` must have a policy - a missing one fails the startup
bind, not the first request.

Idle buckets are evicted from the cache after the longest time-to-full across all policies (24h with
the config above). Anything older has refilled to capacity anyway, so it is indistinguishable from an
absent bucket - which is why this is derived rather than configured; a shorter idle would silently
reset the daily quotas.

## Layout

| | |
|---|---|
| `core/Resource` | the limited resources; domain counterpart of the spec's `resource` enum |
| `core/RateLimiter` | bucket naming + the decision (allowed, limit, remaining, retry-after) |
| `core/ConsumeTokens` | the atomic refill-and-consume step run inside `cache.compute()` |
| `core/Bucket` | cached state: tokens, refill anchor, last outcome |
| `core/RateLimiterSchema` | seeds the compile-time ProtoStream schema for the two cached types |
| `config/RateLimiterProperties` | per-resource policies, derived cache idle time |
| `config/ResourceConverter` | binds `ratelimiter.resources` keys by wire value |
| `config/ValidationConfig`, `config/EnumSizeValidator` | let `@Size` apply to an enum - see below |
| `api/RateLimitApiDelegateImpl` | the only hand-written piece of the API layer |

Generated sources land in `target/generated-sources/openapi` (`RateLimitApi`,
`RateLimitApiController`, `RateLimitApiDelegate`, models).

The spec puts `minLength`/`maxLength` on the `resource` enum, so the generator emits `@Size` on the
generated `ResourceEnum` field - a constraint Hibernate Validator has no validator for, which turns
every request into a 500 (`HV000030`). `ValidationConfig` registers `EnumSizeValidator` to make
`@Size` on an enum measure the constant's wire value instead. Both classes can go once the spec drops
the length bounds from the enum, where they mean nothing anyway.

## Clustering

Run a second instance (`--server.port=8052`) on the same host and the two nodes form an Infinispan
cluster; buckets are distributed with 2 owners, so both instances enforce one shared limit per bucket.

Nodes cluster with nodes of the same cluster name - `ratelimiter.cluster-name`, default
`rate-limiter`. Set it per environment so unrelated instances on one subnet do not find each other;
`ClusteredMarshallingTest` uses it to keep its two nodes away from anything running locally.

A bucket lives on whichever nodes its key hashes to, which is usually not the node handling the
request. All mutation therefore goes through `cache.compute()`, which ships `ConsumeTokens` to the
key's **primary owner** and runs it there - that is what makes refill-and-consume atomic across the
cluster without explicit locking. Never read a bucket, decide, and write it back.

## Serialization (ProtoStream)

Two things cross a node boundary and so have to be turned into bytes: the `Bucket` being stored and
replicated, and the `ConsumeTokens` function being shipped to the owner. The latter is why it is a
record implementing `SerializableBiFunction` rather than a lambda - a lambda cannot be marshalled
across JVMs, so the function carries its parameters as fields and travels with them.

`InfinispanConfig` sets ProtoStream as the user marshaller explicitly. This matters: the Spring Boot
starter otherwise leaves it as `JavaSerializationMarshaller`, and the first time a bucket key is owned
by another node the shipped `ConsumeTokens` is refused by the deserialization allow list
(`ISPN000936`) - a failure a single node never sees. Check the startup log for
`ISPN000556: Starting user marshaller 'org.infinispan.commons.marshall.ProtoStreamMarshaller'`. Note
also that a marshaller passed in explicitly does not pick up `addContextInitializer`; the schema is
registered on the marshaller instance instead.

Infinispan disables Java's built-in serialization by default and marshals with ProtoStream, its
implementation of Protobuf: the layout lives in a schema rather than in the bytes. The
`protostream-processor` (a `provided`-scope dependency) reads the `@Proto` annotations on `Bucket` and
`ConsumeTokens` plus the `@ProtoSchema` on `RateLimiterSchema` at compile time and generates both a
`ratelimiter.proto` schema and a `RateLimiterSchemaImpl` marshaller, which `InfinispanConfig`
registers with `addContextInitializer`. Generated for `Bucket`:

```proto
message Bucket {
   optional int64 tokens = 1;
   optional int64 refillAnchorNanos = 2;
   optional bool  lastAllowed = 3;
}
```

**Field numbers are assigned by declaration order.** Appending a field to `Bucket` or `ConsumeTokens`
is safe; inserting or reordering one renumbers the rest, and a node still on the old numbering will
silently misread the new bytes. A change of that kind needs a full cluster restart rather than a
rolling one.

None of this would be needed for a single node on a `LOCAL` cache - entries would stay on the heap as
ordinary objects and `@Proto` plus `RateLimiterSchema` could go - but that gives up the distribution
that makes this a service rather than a library.
