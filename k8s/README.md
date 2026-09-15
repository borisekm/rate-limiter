# Kubernetes / OpenShift deployment

These manifests are shaped for the target namespace `sa-t`, where the `Service` and the `Deployment`
are produced by a central Helm chart (`bmf.brz.gv.at/central-helmchart: springboot-deployment`) and
are not ours to author. What the platform dictates:

| | given by the platform | consequence |
|---|---|---|
| Service | plain **ClusterIP**, not headless | DNS_PING cannot discover members - see [Discovery](#discovery) |
| Ports | 8080 traffic, 8081 actuator, 8090 debug, 7800/57800 JGroups | the app listens on 8080/8081, not 8051 |
| Selector | `app.kubernetes.io/name` + `app.kubernetes.io/instance`, both `rate-limiter` | the pod labels must carry both |
| Workload | `Deployment`, no volume | no file store; buckets are in memory only |

The app configures itself for all of that: `application.yml` carries a second document guarded by
`spring.config.activate.on-cloud-platform: kubernetes`, which Spring activates whenever it sees the
`KUBERNETES_SERVICE_HOST` that the kubelet injects into every pod. Nothing in the pod spec has to be
set for the service to come up correctly - which is the point, given how little of the pod spec we
control. A local run or `docker compose up` is untouched and stays on 8051 with its store.

| file | what it holds |
|---|---|
| `namespace.yaml` | the `sa-t` namespace - **exists on OpenShift, drop it there** |
| `rbac.yaml` | ServiceAccount + Role + RoleBinding: `get`/`list` on pods, for KUBE_PING |
| `configmap.yaml` | what is genuinely per-environment: cluster name, heap, optional overrides |
| `service.yaml` | a copy of the chart's Service, for test clusters - **the chart owns it on sa-t** |
| `deployment.yaml` | 2 replicas, no volume, actuator probes on 8081 |
| `local-registry/` | lab-only image delivery, see below |
| `push-image.sh` | build, push to every node's registry, pin the digest |

On `sa-t`, `rbac.yaml` is the only piece that has to be applied alongside the chart; everything else
is either the chart's or a test-cluster convenience:

```bash
oc apply -n sa-t -f k8s/rbac.yaml
```

If the chart does not let us set `serviceAccountName` on the Deployment, its pods run as the
namespace's `default` ServiceAccount - point the RoleBinding's subject at that instead. The
alternative is in the file as a comment.

## Discovery

The bundled TCP stack discovers members over **multicast**, which no cluster routes. The usual
Kubernetes answer is DNS_PING against a *headless* Service, whose DNS record returns one A record
per pod. Here the Service is a normal ClusterIP with a virtual IP (`10.245.x.x`), so its DNS returns
that single address and kube-proxy forwards the discovery request to one arbitrary pod. Members
never see each other; every pod forms a cluster of one and enforces its own private limits, which
looks exactly like the service working until you count.

So discovery is **KUBE_PING** (`src/main/resources/jgroups-kubeping.xml`): it asks the API server
for the pods carrying a label selector and pings their pod IPs on 7800 directly, no DNS involved.

| what it needs | where it comes from |
|---|---|
| permission to list pods | `rbac.yaml` - without it the API answers 403 and discovery finds nobody |
| the namespace | the pod's own ServiceAccount mount, read by `PodNamespace` - no downward-API env var required |
| the API server address | `KUBERNETES_SERVICE_HOST` / `_PORT`, injected into every pod |
| a label selector | `app.kubernetes.io/name=rate-limiter`, the default in `jgroups-kubeping.xml` |
| a fixed bind port | 7800: KUBE_PING pings peers on the transport's bind port, it cannot discover a random one |

The selector has to match this deployment's pods and nothing else - every pod it returns gets
contacted on 7800. Override it with a system property if the labels differ:

```
-Djgroups.kubernetes.labels=app.kubernetes.io/name=rate-limiter
```

JGroups reads its `${...}` placeholders as system properties, not environment variables, so an
override goes into `JAVA_OPTS` as a `-D` flag rather than a plain ConfigMap entry.

Ports: `7800` for the transport, `57800` for `FD_SOCK2` (bind port + the stack's 50000 offset). Both
are published by the chart's Service.

Confirm the cluster actually formed - this is the line that matters, `(2)` being the member count:

```bash
oc -n sa-t logs deployment/rate-limiter | grep ISPN000094
# ISPN000094: Received new cluster view for channel rate-limiter-sa-t: [...|1] (2) [...]
```

A 403 from the API server shows up in the same log as a KUBE_PING warning; a cluster of one with no
warning usually means the label selector matched nothing.

## No volume, so no store

The chart's pods get no PersistentVolume, and the `SoftIndexFileStore` must never be shared between
nodes, so persistence is off here. Buckets live in memory, distributed with two owners:

- **rolling update** - buckets survive. `maxUnavailable: 0` keeps the running replicas up while the
  new ones join, and the leaving pod's segments are handed to the pods that stay.
- **every pod stopped at once** - quotas reset. For the 100k/day limits that is a day's allowance
  handed back.
- **scaling** - fine, the data rebalances; this is the case a per-node file store would *not*
  survive.

If quotas must hold across a full outage, the answer is a shared store (JDBC) rather than a volume -
see the root README.

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
`control-1`, `worker-1`, `worker-2`), which is the cheapest way to check discovery and the
label/port wiring before handing anything to the platform team. There `namespace.yaml` and
`service.yaml` do apply, and `service.yaml` adds a NodePort that has no counterpart on `sa-t`.

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
