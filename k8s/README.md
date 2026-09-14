# Kubernetes deployment

Two-node deployment of the rate limiter on the local kubeadm cluster
(`~/.kube/local-k8s-cluster.yaml`, nodes `control-1`, `worker-1`, `worker-2`).

There is no `~/.kube/config` on this machine, so **every** `kubectl` here needs `KUBECONFIG` set.
Without it kubectl falls back to `http://localhost:8080`, where open-webui answers with an HTML page,
and the error blames the manifest rather than the connection:
`failed to download openapi: proto: cannot parse invalid wire-format data`.

```bash
export KUBECONFIG=~/.kube/local-k8s-cluster.yaml
kubectl apply -k k8s
kubectl -n rate-limiter rollout status statefulset/rate-limiter

curl -s -X POST http://192.168.250.11:30851/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
```

| file | what it holds |
|---|---|
| `namespace.yaml` | the `rate-limiter` namespace |
| `storage.yaml` | `StorageClass` + one node-pinned `PersistentVolume` per replica |
| `configmap.yaml` | every environment knob: cluster name, JGroups stack, DNS service, store location |
| `service.yaml` | headless service (DNS_PING) + NodePort service (clients, `30851`) |
| `statefulset.yaml` | 2 replicas, one per worker, a volume each |
| `local-registry/` | lab-only image delivery, see below |
| `push-image.sh` | build, push to every node's registry, pin the digest |

## JGroups

The image defaults to the bundled TCP stack, which discovers members over **multicast** - fine
locally, unavailable in a cluster. The ConfigMap switches discovery to DNS:

| setting | value | why |
|---|---|---|
| `RATELIMITER_JGROUPS_CONFIG` | `org/infinispan/configuration/default-jgroups-kubernetes.xml` | bundled TCP stack whose discovery protocol is `DNS_PING` |
| `JGROUPS_DNS_SERVICE` | `rate-limiter-headless` | the service whose A records list the members |
| `RATELIMITER_CLUSTER_NAME` | `rate-limiter-k8s` | nodes only cluster with the same name |

`DNS_PING` reads `jgroups.dns.query`, a **system property** - JGroups does not look at the
environment - so the StatefulSet passes it as a `-D` flag in `JAVA_OPTS`, built from the
ConfigMap value plus the pod's own namespace from the downward API:

```
-Djgroups.dns.query=$(JGROUPS_DNS_SERVICE).$(POD_NAMESPACE).svc.cluster.local
```

Two details that decide whether the pods find each other at all:

- the headless service sets **`publishNotReadyAddresses: true`**. A pod is not ready until
  Infinispan has started, so without it the members are missing from DNS exactly while they are
  looking for each other, and each forms a cluster of one.
- **`podManagementPolicy: Parallel`**, for the same reason: `OrderedReady` starts pod 1 only
  after pod 0 is ready, and pod 0 becomes ready having found nobody.

Ports: `7800` for the transport, `57800` for `FD_SOCK2` (bind port + the stack's 50000 offset).

Confirm the cluster actually formed - this is the line that matters, `(2)` being the member count:

```bash
kubectl -n rate-limiter logs rate-limiter-0 | grep ISPN000094
# ISPN000094: Received new cluster view for channel rate-limiter-k8s: [...|1] (2) [...]
```

## Which pod answered

Every response carries an `X-Served-By` header with the pod name - the StatefulSet passes
`metadata.name` in as `RATELIMITER_INSTANCE_ID`, and the app falls back to the hostname when it is
unset. It rides in a header rather than the body because the response schema belongs to the API
contract in `rate-limiter-api.yaml`, which this repository does not own.

```bash
curl -si -X POST http://192.168.250.11:30851/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}' | grep -i x-served-by
# X-Served-By: rate-limiter-0
```

Useful for confirming the NodePort really spreads calls while `remaining` keeps counting down
across both pods - which is the whole point of the shared bucket.

## Storage

The bucket store is a per-node `SoftIndexFileStore` (`shared=false`), so this is a StatefulSet
with a `volumeClaimTemplate`, never a Deployment - two pods must never share a directory, and a
pod that moves must take its store with it.

The cluster has no dynamic provisioner, so `storage.yaml` declares one `hostPath` PV per worker,
pinned with `nodeAffinity` and bound late (`WaitForFirstConsumer`), and the StatefulSet keeps one
pod per node with an anti-affinity rule. On a cluster with a real `StorageClass`, delete
`storage.yaml` and point `volumeClaimTemplates[].spec.storageClassName` at that class.

`hostPath` volumes get no ownership management from kubelet, so an init container chowns `/data`
to uid 1000 - the user the image runs as. A provisioner that honours `fsGroup` makes it redundant.

Scaling past 2 replicas needs another PV per new replica (and a node to pin it to). Note that
changing the node count can orphan buckets - the store holds only the segments its node owned; see
the root README.

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

A real environment replaces this with one shared registry: set `images[].newName` in
`kustomization.yaml` to it and drop `local-registry/`.
