#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

QUINT_VERSION="0.33.1"
QUINT=(npx --yes "@informalsystems/quint@${QUINT_VERSION}")
REGISTRY="checks.json"

if ! command -v jq >/dev/null 2>&1; then
  echo "models/check.sh requires jq" >&2
  exit 2
fi

if [[ ! -f "$REGISTRY" ]]; then
  echo "missing $REGISTRY" >&2
  exit 2
fi

for file in q1_relationship.qnt q2_order.qnt q3_resend.qnt q4_reconnect.qnt q5_rekey.qnt q6_delivery.qnt; do
  "${QUINT[@]}" typecheck "$file" >/dev/null
done

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

count=$(jq 'length' "$REGISTRY")
for index in $(seq 0 $((count - 1))); do
  file=$(jq -r ".[$index].file" "$REGISTRY")
  main=$(jq -r ".[$index].main" "$REGISTRY")
  backend=$(jq -r ".[$index].backend" "$REGISTRY")
  expected=$(jq -r ".[$index].sourceSha256" "$REGISTRY")
  invariants=$(jq -r ".[$index].invariants | join(\",\")" "$REGISTRY")
  temporal=$(jq -r ".[$index].temporal | join(\",\")" "$REGISTRY")
  max_steps=$(jq -r ".[$index].maxSteps" "$REGISTRY")
  if [[ -n "$temporal" ]]; then
    max_steps=$(jq -r ".[$index].temporalMaxSteps // .[$index].maxSteps" "$REGISTRY")
  fi
  actual=$(hash_file "$file")
  if [[ "$actual" != "$expected" ]]; then
    echo "source hash mismatch: $file" >&2
    exit 1
  fi
  trace=$(jq -r ".[$index].trace // empty" "$REGISTRY")
  if [[ -n "$trace" ]]; then
    expected_trace=$(jq -r ".[$index].traceSha256" "$REGISTRY")
    actual_trace=$(hash_file "$trace")
    if [[ "$actual_trace" != "$expected_trace" ]]; then
      echo "trace hash mismatch: $trace" >&2
      exit 1
    fi
  fi
  args=("$file" "--main=$main" "--backend=$backend" "--max-steps=$max_steps")
  if [[ -n "$invariants" ]]; then
    args+=("--invariant=$invariants")
  fi
  if [[ -n "$temporal" ]]; then
    args+=("--temporal=$temporal")
  fi
  echo "models: $main backend=$backend invariants=${invariants:-none} temporal=${temporal:-none}"
  "${QUINT[@]}" verify "${args[@]}" >/tmp/yochat-quint-"$index".log 2>&1
  tail -3 /tmp/yochat-quint-"$index".log
done

echo "QUINT_CHECKS PASS"
