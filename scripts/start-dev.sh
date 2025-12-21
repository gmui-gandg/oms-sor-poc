#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

NO_INFRA=false
if [[ ${1:-} == "--no-infra" || ${1:-} == "-n" ]]; then
  NO_INFRA=true
fi

if [ "$NO_INFRA" = false ]; then
  echo "Starting infra via docker compose..."
  docker compose -f infra/docker/docker-compose.yml up -d
fi

echo "Building and running services/oms-ingest (skip tests)..."
cd services/oms-ingest
mvn -DskipTests spring-boot:run
