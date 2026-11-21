#!/usr/bin/env bash
# Checks that the quickstart overlay passes the `restricted` Pod Security
# Standard. Run from any directory. Requires Docker, kind and kubectl.
#
# The script creates its own kind cluster with its own kubeconfig and deletes
# both on exit. It never reads or changes the current kube context.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY="$REPO_ROOT/deploy/k8s/overlays/quickstart"
NAMESPACE=platformup2date
CLUSTER="pu2d-pss-$$"
TMP_DIR="$(mktemp -d)"
export KUBECONFIG="$TMP_DIR/kubeconfig"

cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

kind create cluster --name "$CLUSTER" --wait 120s

# Pod Security admission rejects only Pods in `enforce` mode: a Deployment
# with a bad pod template is still admitted. `warn` mode evaluates the pod
# template of a workload resource, and --warnings-as-errors makes kubectl fail
# on that warning. A server-side dry run is sufficient, because admission runs
# on a dry run.
kubectl create namespace "$NAMESPACE" --save-config
kubectl label namespace "$NAMESPACE" \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/warn=restricted

# Control: a Deployment with no securityContext must fail. If it passes, the
# check below proves nothing.
if kubectl --namespace "$NAMESPACE" create deployment pss-control \
  --image=registry.k8s.io/pause:3.10 --dry-run=server --warnings-as-errors \
  >"$TMP_DIR/control.log" 2>&1; then
  cat "$TMP_DIR/control.log" >&2
  echo 'FAIL: the control Deployment has no securityContext, but the cluster accepted it' >&2
  exit 1
fi
grep -q 'violate PodSecurity "restricted' "$TMP_DIR/control.log" || {
  cat "$TMP_DIR/control.log" >&2
  echo 'FAIL: the control Deployment failed for a reason other than Pod Security' >&2
  exit 1
}
echo 'PASS: control Deployment without securityContext is rejected'

kubectl apply --dry-run=server --warnings-as-errors -k "$OVERLAY"
echo 'PASS: the quickstart overlay passes the restricted Pod Security Standard'
