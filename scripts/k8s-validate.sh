#!/usr/bin/env bash
# Builds both kustomize overlays and validates every resource against the Kubernetes (and KEDA CRD) schemas.
set -euo pipefail
cd "$(dirname "$0")/.."
for overlay in k8s k8s/keda; do
  echo "== $overlay"
  docker run --rm -v "$PWD/k8s:/k8s" -w / registry.k8s.io/kubectl:v1.34.0 kustomize "/$overlay" 2>/dev/null \
    | docker run --rm -i ghcr.io/yannh/kubeconform:latest -strict -summary -schema-location default \
        -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
done
