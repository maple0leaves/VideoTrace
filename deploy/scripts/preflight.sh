#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.production}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/docker-compose.prod.yml")

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

value_of() {
  local key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r'
}

require_secret() {
  local key="$1" min_length="$2"
  local value
  value="$(value_of "$key")"
  [[ -n "$value" ]] || fail "$key 未填写"
  [[ ${#value} -ge $min_length ]] || fail "$key 至少需要 $min_length 位"
  [[ "$value" != replace-* ]] || fail "$key 仍是示例占位符"
}

command -v docker >/dev/null || fail "未安装 Docker"
docker compose version >/dev/null || fail "未安装 Docker Compose v2"
[[ -f "$ENV_FILE" ]] || fail "找不到配置文件：$ENV_FILE"

shared_network="$(value_of SHARED_NETWORK)"
shared_network="${shared_network:-shared-platform}"
docker network inspect "$shared_network" >/dev/null 2>&1 ||
  fail "公共 Docker 网络不存在；请先启动 Project-Deployment"

public_origin="$(value_of PUBLIC_ORIGIN)"
[[ "$public_origin" =~ ^https?://[^/[:space:]]+$ ]] ||
  fail "PUBLIC_ORIGIN 必须是完整的 http/https 来源且不能包含路径"

data_dir="$(value_of VIDEOTRACE_DATA_DIR)"
[[ "$data_dir" == /* ]] || fail "VIDEOTRACE_DATA_DIR 必须是服务器上的绝对路径"
available_kb="$(df -Pk "$(dirname "$data_dir")" | awk 'NR==2 {print $4}')"
[[ "$available_kb" -ge 52428800 ]] || fail "VideoTrace 数据盘可用空间不足 50 GiB"

require_secret DB_PASSWORD 16
require_secret REDIS_PASSWORD 16
require_secret VECTOR_DB_PASSWORD 16
require_secret MINIO_SECRET_KEY 16
require_secret VIDEOTRACE_DEEPSEEK_API_KEY 8
require_secret SILICONFLOW_API_KEY 8

vector_dimension="$(value_of VECTOR_DIMENSION)"
[[ "$vector_dimension" =~ ^[0-9]+$ ]] || fail "VECTOR_DIMENSION 必须是正整数"
(( vector_dimension > 0 && vector_dimension <= 2000 )) ||
  fail "VECTOR_DIMENSION 必须位于 1 到 2000"

if [[ -r /proc/meminfo ]]; then
  memory_kb="$(awk '/MemTotal/ {print $2}' /proc/meminfo)"
  [[ "$memory_kb" -ge 15728640 ]] || fail "两个项目共享部署建议至少 15 GiB 内存"
fi

"${COMPOSE[@]}" config --quiet
echo "OK: VideoTrace 生产配置、公共网络和主机资源检查通过。"
