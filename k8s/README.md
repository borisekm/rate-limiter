# Kubernetes / OpenShift deployment

These manifests are shaped for the target namespace `sa-t`, where the `Service` and the `Deployment`
are produced by a central Helm chart (`bmf.brz.gv.at/central-helmchart: springboot-deployment`) and
are not ours to author. What the platform dictates:

| | given by the platform | consequence |
|---|---|---|
| Service | plain **ClusterIP** | fine: the replicas do not talk to each other, only to Data Grid |
| Ports | 8080 traffic, 8081 actuator, 8090 debug, 7800/57800 JGroups | the app listens on 8080/8081, not 8051; the JGroups ports are unused |
| Selector | `app.kubernetes.io/name` + `app.kubernetes.io/instance`, both `rate-limiter` | the pod labels must carry both |
| Workload | `Deployment`, no volume | fine: the app is stateless, the buckets live in Data Grid |
| Data Grid | Red Hat Data Grid 8.6 (operator) in the same namespace | the bucket store - see [Data Grid](#data-grid) |

The app configures itself for all of that: `application.yml` carries a second document guarded by
`spring.config.activate.on-cloud-platform: kubernetes`, which Spring activates whenever it sees the
`KUBERNETES_SERVICE_HOST` that the kubelet injects into every pod. Nothing in the pod spec has to be
set for the service to come up correctly - which is the point, given how little of the pod spec we
control. A local run or `docker compose up` is untouched and stays on 8051.

| file | what it holds |
|---|---|
| `namespace.yaml` | the `sa-t` namespace - **exists on OpenShift, drop it there** |
| `configmap.yaml` | what is genuinely per-environment: Data Grid address, fail-open/closed, heap |
| `service.yaml` | a copy of the chart's Service, for test clusters - **the chart owns it on sa-t** |
| `deployment.yaml` | 2 replicas, no volume, actuator probes on 8081, credentials from a Secret |
| `local-registry/` | lab-only image delivery, see below |
| `push-image.sh` | build, push to every node's registry, pin the digest |

On `sa-t` the chart owns the Deployment and Service, so what has to exist alongside it is the
ConfigMap values and the credentials Secret.

## Data Grid

The buckets live in a cache on the namespace's Data Grid cluster, reached over Hot Rod. The
replicas never talk to each other, so there is no clustering, discovery, RBAC or volume on our side.

| what the app needs | where it comes from |
|---|---|
| the server address | `RATELIMITER_INFINISPAN_SERVERS` in `configmap.yaml`: the operator's Service, named after the `Infinispan` CR (`oc get infinispan`), port 11222 |
| credentials | `RATELIMITER_INFINISPAN_USERNAME` / `_PASSWORD` from the Secret `rate-limiter-datagrid` |
| trust for the TLS certificate | the operator's default certificate is signed by the OpenShift service CA, which OpenShift mounts into every pod at `/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt`; `application.yml` points the client there |
| the cache | the app creates `rate-limit-buckets` on first use if it does not exist (distributed, 2 owners), so the user needs permission to create caches |

The Secret, with a Data Grid user that may create caches and read/write them (the operator's
generated `developer` user can; its password is in `<cr-name>-generated-secret`):

```bash
oc -n sa-t create secret generic rate-limiter-datagrid \
  --from-literal=RATELIMITER_INFINISPAN_USERNAME=developer \
  --from-literal=RATELIMITER_INFINISPAN_PASSWORD='<password>'
```

If the chart cannot reference a Secret via `envFrom`, the same two variables can come from wherever
the chart does take environment from.

Things that break the connection, and how they look: every check answers with the
`RATELIMITER_WHEN_STORE_UNAVAILABLE` fallback and an `X-RateLimit-Degraded: store-unavailable`
header, and the log carries `Bucket store unavailable, allowing|denying all calls` at most every 30s,
with the cause.

- `SSLHandshakeException` / certificate errors: the CR uses a custom certificate rather than the
  service CA, or the chart disables the ServiceAccount token mount (`automountServiceAccountToken:
  false`), which also removes `service-ca.crt`. Point `RATELIMITER_INFINISPAN_TLS_TRUST_STORE` at a
  mounted CA bundle, or set `RATELIMITER_INFINISPAN_TLS_ENABLED=false` if the CR has
  `endpointEncryption.type: None`.
- `SecurityException` / authentication failed: wrong credentials, or a user without permission to
  create the cache - have the Data Grid owner create `rate-limit-buckets` (see the root README for
  the definition), and the app will use it as it is.
- connection timeouts after the first request: the client connects to Data Grid pod IPs directly
  (`HASH_DISTRIBUTION_AWARE`); if a NetworkPolicy allows only the Service, set
  `RATELIMITER_INFINISPAN_INTELLIGENCE=BASIC`.

`/actuator/health` (port 8081) lists `bucketStore` as `UP` or `DOWN`. It is **not** part of
`/actuator/health/readiness`: with Data Grid down every pod is equally degraded, and pulling them all
out of the Service would turn fail-open into an outage.

What survives what:

- **rolling update of rate-limiter** - everything; the pods hold no state.
- **one Data Grid pod restarting** - buckets survive, each is held by two owners.
- **the whole Data Grid cluster restarting** - quotas reset, unless the cache is given a persistent
  store on the Data Grid side. For the 100k/day limits that is a day's allowance handed back.

## Which pod answered

Every response carries an `X-Served-By` header with the pod name - the Deployment passes
`metadata.name` in as `RATELIMITER_INSTANCE_ID`, and the app falls back to the hostname when it is
unset. It rides in a header rather than the body because the response schema belongs to the API
contract in `rate-limiter-api.yaml`, which this repository does not own.

```bash
curl -si -X POST http://<route>/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}' | grep -i x-served-by
# X-Served-By: rate-limiter-7c9f8b6d4-2xqkz
```

Useful for confirming the Service really spreads calls while `remaining` keeps counting down across
both pods - which is the whole point of the shared bucket.

## Test cluster

The same manifests run on the local kubeadm cluster (`~/.kube/local-k8s-cluster.yaml`, nodes
`control-1`, `worker-1`, `worker-2`), which is the cheapest way to check the label/port wiring
before handing anything to the platform team. There `namespace.yaml` and `service.yaml` do apply,
and `service.yaml` adds a NodePort that has no counterpart on `sa-t`.

That cluster has no Data Grid operator and no service CA, so it needs an Infinispan server of its
own (e.g. `quay.io/infinispan/server:15.2` behind a Service named `datagrid`, with `USER`/`PASS`
matching the Secret) and TLS switched off with `RATELIMITER_INFINISPAN_TLS_ENABLED=false`. Without
one the pods still start and answer - with the fallback.

There is no `~/.kube/config` on this machine, so **every** `kubectl` here needs `KUBECONFIG` set.
Without it kubectl falls back to `http://localhost:8080`, where open-webui answers with an HTML page,
and the error blames the manifest rather than the connection:
`failed to download openapi: proto: cannot parse invalid wire-format data`.

```bash
export KUBECONFIG=~/.kube/local-k8s-cluster.yaml
kubectl apply -k k8s
kubectl -n sa-t rollout status deployment/rate-limiter

curl -s -X POST http://192.168.250.11:30851/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
```

## Image delivery (lab only)

The nodes have no shared registry and cannot pull over plain HTTP from the developer host without
`/etc/containerd/certs.d` changes on every node. `local-registry/registry.yaml` sidesteps that: a
DaemonSet runs a registry **on each worker's own loopback** (`hostNetwork`), and containerd
resolves a `localhost:5000/...` reference over HTTP with no node configuration at all.

Each node's registry is independent, so the image is pushed once per node through a port-forward.
`push-image.sh` does that, and then pins the digest it pushed in `kustomization.yaml`:

```bash
kubectl apply -f k8s/local-registry/registry.yaml   # once
k8s/push-image.sh                                   # build, push to every node, pin the digest
kubectl apply -k k8s                                # roll out
```

The digest is what makes a rebuild actually roll: the tag stays `0.1.0-SNAPSHOT`, so under
`imagePullPolicy: IfNotPresent` a node would otherwise keep the image it already has.

On `sa-t` the image comes from the platform registry: replace the `images:` entry in
`kustomization.yaml` with that reference and drop `local-registry/`.
