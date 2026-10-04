# cert-manager (lab cluster)

The lab cluster runs [cert-manager](https://cert-manager.io) to issue and renew TLS certificates.
Its one issuer, the ClusterIssuer `lab-ca`, signs with the lab CA in
`~/k8s-lab-build/infinispan-tls/`, the same CA that signed Data Grid's certificate. A client that
trusts `ca.crt` therefore trusts the rate limiter and Data Grid alike.

What uses it today: the rate limiter's HTTPS certificate, `../certificate.yaml` → Secret
`sa-t/rate-limiter-tls`. This is lab-only. On `sa-t` the certificate comes from the platform (see
`../README.md`).

| | |
|---|---|
| version | v1.21.2, from the official static manifest (no Helm on this machine) |
| namespace | `cert-manager` (controller, cainjector, webhook) |
| issuer | ClusterIssuer `lab-ca` (`cluster-issuer.yaml`), type `ca` |
| CA key | Secret `cert-manager/lab-ca`, created from the files. It is **not** in this repository |

## Install (once per cluster)

```bash
export KUBECONFIG=~/.kube/local-k8s-cluster.yaml

kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml
kubectl -n cert-manager rollout status deploy/cert-manager-webhook

# The CA the issuer signs with: certificate and key, as a TLS Secret next to cert-manager.
CA_DIR=~/k8s-lab-build/infinispan-tls
kubectl -n cert-manager create secret tls lab-ca --cert=$CA_DIR/ca.crt --key=$CA_DIR/ca.key

kubectl apply -f k8s/cert-manager/cluster-issuer.yaml
kubectl get clusterissuer lab-ca          # READY True
```

The issuer is applied after cert-manager's webhook is up, never in the same `apply`: before that
the API server does not know the `ClusterIssuer` kind yet.

To upgrade, apply the next release's manifest the same way and change the version here.

## Usage

### Is the app's certificate healthy?

```bash
kubectl -n sa-t get certificate
# NAME               READY   SECRET             AGE
# rate-limiter-tls   True    rate-limiter-tls   ...

kubectl -n sa-t get certificate rate-limiter-tls \
  -o jsonpath='{.status.notAfter}{"  renew at "}{.status.renewalTime}{"\n"}'
# 2027-01-02T07:00:55Z  renew at 2026-12-03T07:00:55Z
```

`kubectl -n sa-t describe certificate rate-limiter-tls` shows the events: issued, renewed, or why
not.

### What is the app actually serving?

The Secret is what cert-manager wrote; the pods serve it once the kubelet has synced the volume
(about a minute) and the app has reloaded it. Compare the two:

```bash
kubectl -n sa-t get secret rate-limiter-tls -o jsonpath='{.data.tls\.crt}' | base64 -d \
  | openssl x509 -noout -serial -enddate -ext subjectAltName

echo | openssl s_client -connect 192.168.250.11:30852 \
  -CAfile ~/k8s-lab-build/infinispan-tls/ca.crt 2>/dev/null \
  | grep -E 'Verify return code|subject=|issuer='
# subject=CN = rate-limiter.sa-t.svc
# issuer=CN = k8s-lab Infinispan CA
# Verify return code: 0 (ok)
```

A reload shows up in the app log as two `bundle-watcher` lines, one per port:

```bash
kubectl -n sa-t logs deploy/rate-limiter | grep bundle-watcher
```

### Renew now

There is no `cmctl` here. Deleting the Secret makes cert-manager issue a new one within seconds. The
running pods keep the files they have until the new ones arrive, and then reload them:

```bash
kubectl -n sa-t delete secret rate-limiter-tls
kubectl -n sa-t wait certificate/rate-limiter-tls --for=condition=Ready
kubectl -n sa-t get certificaterequest        # one more, rate-limiter-tls-<n>
```

The same thing happens on its own at `renewalTime`, 30 days before expiry. With
`rotationPolicy: Always`, every renewal also gets a fresh key.

### Add a name to the certificate

Edit `dnsNames` / `ipAddresses` in `../certificate.yaml` (for example after adding a node), then:

```bash
kubectl apply -k k8s      # cert-manager reissues because the spec changed
```

### A certificate for something else

Any namespace can use the ClusterIssuer:

```bash
kubectl apply -f - <<'EOF'
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: demo-tls
  namespace: default
spec:
  secretName: demo-tls
  issuerRef:
    kind: ClusterIssuer
    name: lab-ca
  dnsNames: [demo.default.svc]
EOF

kubectl -n default wait certificate/demo-tls --for=condition=Ready
kubectl -n default get secret demo-tls -o jsonpath='{.data.tls\.crt}' | base64 -d \
  | openssl verify -CAfile ~/k8s-lab-build/infinispan-tls/ca.crt     # stdin: OK

kubectl -n default delete certificate demo-tls && kubectl -n default delete secret demo-tls
```

Deleting a Certificate leaves its Secret behind, so delete both.

### When it does not issue

```bash
kubectl -n sa-t describe certificate rate-limiter-tls     # events, last failure
kubectl -n sa-t get certificaterequest,order -o wide       # (order only for ACME, not here)
kubectl describe clusterissuer lab-ca                      # Secret lab-ca missing or wrong?
kubectl -n cert-manager logs deploy/cert-manager --since=10m
```

- **ClusterIssuer not Ready:** the Secret `cert-manager/lab-ca` is missing, or it does not have both
  `tls.crt` and `tls.key`.
- **Certificate stuck, app still serving the old one:** check the Secret's serial against
  `s_client` above. If the Secret is new and the app still serves the old certificate after a
  couple of minutes, the reload failed, and the app log will say why.

## Data Grid's certificate

Data Grid's certificate (Secret `infinispan/infinispan-tls`) is still the hand-made one from
`~/k8s-lab-build/infinispan-tls/`, valid until 2028. It could come from the same issuer: a
Certificate in the `infinispan` namespace with `secretName: infinispan-tls` and the SANs that
certificate has today. The operator reads that Secret by name. Whether the Data Grid pods pick up a
renewal without a restart has not been tried here.
