# rate-limiter

Token-bucket rate limiter service on Spring Boot 4 + embedded Infinispan 16.

```bash
mvn clean verify          # generates the API, compiles, runs tests; clean also wipes ./data (see Persistence)
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
`refill-tokens`, capped at `capacity`. Mid-period nothing accrues. In Bucket4j terms that is
`refillIntervally`; `refillGreedy` would be the smooth drip.

The algorithm itself is [Bucket4j](https://bucket4j.com), running distributed over the Infinispan
cache (`bucket4j_jdk17-core` + `bucket4j_jdk17-infinispan`). Each policy becomes a `BucketConfiguration`
with one `Bandwidth`, built once at startup in `core/RateLimiter`.

`retryAfterMillis` is always the wait until the next refill that helps, on allowed and refused calls
alike: when refused, until enough tokens are back; when allowed, until the next refill lands. It is
never 0 (the spec's "0 otherwise" wording is out of date), so a caller can pace itself before it is
refused. The `Retry-After` header is still sent on 429s only. Every value of `Resource` must have a policy - a missing one fails the startup
bind, not the first request.

Buckets are dropped from the cache after the longest time-to-full across all policies (24h with the
config above). Anything older has refilled to capacity anyway, so it is indistinguishable from an
absent bucket - which is why this is derived rather than configured; a shorter one would silently
reset the daily quotas. It is applied as `lifespan` rather than `maxIdle`, because Infinispan refuses
max-idle alongside a cache store without passivation (ISPN000651) and every check writes the entry
anyway, which refreshes the lifespan just as an access would refresh max-idle.

## Persistence

Buckets are written to a `SoftIndexFileStore` (part of `infinispan-core` - no extra dependency), so
state survives a restart of every node. Without it a deploy hands back a full bucket to everyone,
which for the 100k/day quotas means a whole day's allowance.

```yaml
ratelimiter:
  persistence:
    enabled: true
    location: ./data/rate-limiter    # per node - two nodes must never share a directory
```

The store lives in a subdirectory named after the state format (`bucket4j-v2`). Persisted entries can
only be read back by code that still has a marshaller for them, so a change of bucket representation
would otherwise make every request touching an old key fail with
`No marshaller registered for Protobuf type ...`. Bumping `STATE_FORMAT` in `InfinispanConfig` on such
a change starts a clean store instead; the previous directory becomes inert and can be deleted. This
is what happened moving from the hand-written buckets to Bucket4j (`bucket4j-v1`), and again moving to
Infinispan 16, whose store a 15.x node wrote cannot be assumed readable (`bucket4j-v2`). The older
directories are dead weight and safe to remove.

Writes are **write-behind** (`async().enable()`), so a hard kill can lose the last few writes and
leave a quota slightly over-permissive; in exchange a check never waits on the disk. `preload` is off:
`cache.compute()` reads through to the store on a miss, so a bucket is restored the first time it is
touched rather than loading every bucket into heap at boot.

What this survives:

| | |
|---|---|
| One node restarting, cluster stays up | Fine - the other owner has the data anyway |
| Every node restarting, same node count | Fine - each node reloads the segments it owns |
| Restarting with a **different** node count | **Buckets can be lost.** The store is per node (`shared=false`) and holds only that node's segments; after a topology change a node may hold entries it no longer owns |

That last row is what matters on OpenShift: a file store needs a StatefulSet with a PVC per pod, and a
plain Deployment loses buckets whenever pods move or scale. If quotas must hold across scaling, use a
**shared** store instead - add `org.infinispan:infinispan-cachestore-jdbc` (the imported BOM supplies
the version), point every node at one database and set `.shared(true)`.

On OpenShift the store is therefore **off** (`ratelimiter.persistence.enabled: false`, set in the
Kubernetes document of `application.yml`): the central Helm chart that owns the Deployment gives the
pods no volume, and a store on the container filesystem would be a different, empty store after every
restart anyway. Buckets then live in memory with two owners, so a *rolling* restart carries them over
- the surviving pods hand their segments to the new ones - while a simultaneous stop of every pod
resets the quotas. See [k8s/README.md](k8s/README.md).

Refill anchors are epoch milliseconds, not `System.nanoTime()`, precisely so that persisted state
still means something in a new JVM - and so that nodes on different machines agree, which they did not
before.

## Layout

| | |
|---|---|
| `core/Resource` | the limited resources; domain counterpart of the spec's `resource` enum |
| `core/RateLimiter` | bucket naming, the Bucket4j configuration per resource, and the decision |
| `config/RateLimiterProperties` | per-resource policies, derived bucket lifespan |
| `config/InfinispanConfig` | cluster, marshaller, file store, and the wall `Clock` bean |
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
the two-node tests use it to keep away from anything running locally.

The transport is **TCP**, not the UDP stack Infinispan defaults to - multicast is unavailable on
OpenShift and most cloud networks, so this is the transport we deploy on. The bundled stacks differ
only in how members are *discovered*:

| `ratelimiter.jgroups-config` | discovery | where |
|---|---|---|
| `org/infinispan/configuration/default-jgroups-tcp.xml` (default) | MPING (multicast) | local, dev |
| `jgroups-kubeping.xml` | KUBE_PING (Kubernetes API) | OpenShift |
| `org/infinispan/configuration/default-jgroups-kubernetes.xml` | DNS_PING (headless service) | Kubernetes, given a headless service |

So the default still discovers over multicast even though the data path is TCP. On Kubernetes the app
switches itself to `jgroups-kubeping.xml`, in the `application.yml` document guarded by
`spring.config.activate.on-cloud-platform: kubernetes` - no environment variable needed, which
matters when the Deployment comes from a chart we do not own.

**Why KUBE_PING and not DNS_PING.** DNS_PING needs a *headless* Service, whose DNS record answers
with one A record per pod. The Service we are given on OpenShift is a plain ClusterIP with a virtual
IP, whose DNS answers with that one address - kube-proxy then load-balances the discovery request to
a single arbitrary pod, so members never see each other and every pod forms a cluster of one.
KUBE_PING skips DNS and asks the API server for the pods matching a label selector, then pings their
pod IPs directly. It costs one dependency (`org.jgroups.kubernetes:jgroups-kubernetes`) and a Role
letting the pod's ServiceAccount `list` pods in its own namespace; without that Role the API call is
refused with 403 and, again, nobody finds anybody. Where a headless service *is* available, DNS_PING
is the simpler choice:

```
-Dratelimiter.jgroups-config=org/infinispan/configuration/default-jgroups-kubernetes.xml
-Djgroups.dns.query=rate-limiter-headless.my-namespace.svc.cluster.local
```

`KubePingStackTest` parses `jgroups-kubeping.xml` and asserts KUBE_PING is really in it - the stack
is selected only inside a pod, and a mistake there shows up as pods that start happily and each form
a cluster of one.

JGroups binds port 7800 and takes the next free one when it is busy, so several nodes can share a
host (`ISPN000079` in the log names the port a node actually took).

The two-node tests use `src/test/resources/jgroups-test-tcpping.xml` instead - the same TCP stack with
static loopback discovery - because MPING binds whichever site-local address comes first, and on a
machine with a VPN or a VMware/Hyper-V adapter that is a virtual NIC multicast never crosses. There
the nodes would each form a cluster of one and the shared-limit tests would fail for reasons that have
nothing to do with the code.

A bucket lives on whichever nodes its key hashes to, which is usually not the node handling the
request. Bucket4j's `InfinispanProxyManager` therefore ships an **entry processor** to the key's
primary owner and runs the refill-and-consume there - atomic across the cluster without explicit
locking. Never read a bucket, decide, and write it back.

## Serialization (ProtoStream)

Two things cross a node boundary and so have to be turned into bytes: the bucket state being stored
and replicated, and Bucket4j's entry processor being shipped to the owner.

`InfinispanConfig` sets ProtoStream as the user marshaller explicitly. This matters: the Spring Boot
starter otherwise leaves it as `JavaSerializationMarshaller`, and the first time a bucket key is owned
by another node the shipped entry processor is refused by the deserialization allow list
(`ISPN000936`) - a failure a single node never sees. Check the startup log for
`ISPN000556: Starting user marshaller 'org.infinispan.commons.marshall.ProtoStreamMarshaller'`.

Bucket4j's schema - `Bucket4jProtobufContextInitializer`, covering its processor and result types - is
supplied as a configured context initializer (`serialization().addContextInitializer(...)`), which
registers it in both the user and the global ProtoStream context. The global one is what matters:
since Infinispan 16, a ProtoStream user marshaller makes the global marshaller bypass the user
marshaller entirely (`GlobalMarshaller.skipUserMarshaller`) and write user objects with the global
context. Registering the schema on the marshaller instance instead - the Infinispan 15 arrangement,
where an explicitly supplied marshaller ignored `addContextInitializer` - leaves the global context
without it and every cross-node bucket operation fails with
`No marshaller registered for object of Java type ... InfinispanProcessor`.

Bucket state itself is stored as `byte[]`: Bucket4j serialises its own state, so the cache never sees
an application type. That is why this project no longer defines any `@Proto` records or a ProtoStream
schema of its own, and why `protostream-processor` is no longer a dependency.

None of this would be needed for a single node on a `LOCAL` cache - but that gives up the distribution
that makes this a service rather than a library.

## Containers and Kubernetes

`Dockerfile` builds the service (the OpenAPI generation runs inside the build stage) onto a JRE
image that keeps its bucket store in the `/data` volume; `compose.yaml` runs a single node locally.

```bash
docker compose up -d
curl -s -X POST localhost:8051/v1/rate/check -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
```

Every response carries an `X-Served-By` header naming the node that answered (the pod name on
Kubernetes, the hostname otherwise; override with `ratelimiter.instance-id`). It is a header and not
a response field because the response schema belongs to the API contract.

`k8s/` deploys two clustered nodes as a Deployment, shaped for an OpenShift namespace whose Service
and Deployment come from a central Helm chart: ports 8080 (traffic) and 8081 (actuator), KUBE_PING
discovery plus the Role it needs, and no volume. See [k8s/README.md](k8s/README.md).

Locally the app stays on 8051 with its store intact; the Kubernetes settings apply only when Spring
detects the platform, which it does from the `KUBERNETES_SERVICE_HOST` every pod gets.
