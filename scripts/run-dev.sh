#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null || { echo 'Java 17+ is required.' >&2; exit 1; }
command -v mvn >/dev/null || { echo 'Maven 3.9+ is required.' >&2; exit 1; }
exec mvn spring-boot:run "$@"
