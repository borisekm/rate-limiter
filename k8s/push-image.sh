#!/usr/bin/env bash
# Build the image and push it into every node's local registry (see README.md), then pin
# kustomization.yaml to the digest that was pushed.
#
# The digest matters: the tag stays 0.1.0-SNAPSHOT across rebuilds, and with imagePullPolicy
# IfNotPresent a node that already has that tag would keep running the old image. Pinning the
# digest makes "apply" a real rollout.
set -euo pipefail

IMAGE=${IMAGE:-rate-limiter:0.1.0-SNAPSHOT}
LOCAL=localhost:5000/rate-limiter:0.1.0-SNAPSHOT
here=$(cd "$(dirname "$0")" && pwd)

docker build -t "$IMAGE" "$here/.."
docker tag "$IMAGE" "$LOCAL"

digest=""
for pod in $(kubectl -n local-registry get pods -o name); do
  node=$(kubectl -n local-registry get "$pod" -o jsonpath='{.spec.nodeName}')
  kubectl -n local-registry port-forward "$pod" 5000:5000 >/dev/null 2>&1 &
  pf=$!
  trap 'kill $pf 2>/dev/null || true' EXIT
  for _ in $(seq 20); do curl -sf localhost:5000/v2/ >/dev/null && break; sleep 0.5; done

  echo "pushing to $node"
  out=$(docker push "$LOCAL")
  digest=$(grep -o 'sha256:[0-9a-f]\{64\}' <<<"$out" | head -1)

  kill $pf 2>/dev/null || true
  wait $pf 2>/dev/null || true
  trap - EXIT
done

[ -n "$digest" ] || { echo "no digest - was anything pushed?" >&2; exit 1; }
sed -i -E "s|^( +digest: ).*|\1$digest|" "$here/kustomization.yaml"
echo "pinned $digest in kustomization.yaml"
