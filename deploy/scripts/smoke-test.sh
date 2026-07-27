#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.production}"
PUBLIC_ORIGIN="$(sed -n 's/^PUBLIC_ORIGIN=//p' "$ENV_FILE" | tail -n 1 | tr -d '\r')"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/docker-compose.prod.yml")

[[ -n "$PUBLIC_ORIGIN" ]] || {
  echo "ERROR: .env.production 缺少 PUBLIC_ORIGIN" >&2
  exit 1
}

curl --fail --silent --show-error "$PUBLIC_ORIGIN/" >/dev/null
curl --fail --silent --show-error "$PUBLIC_ORIGIN/api/actuator/health" |
  python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'

"${COMPOSE[@]}" ps
echo "OK: VideoTrace 公网页面和后端健康检查通过。"
