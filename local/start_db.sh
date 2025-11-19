#!/bin/bash
# start_db.sh - Starts the local Oracle ADB Free container and prepares the wallet
# Usage: ./start_db.sh [--clean]

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
CONTAINER_NAME="adb-free"
WALLET_DIR="./adb_wallet"
ADMIN_PASSWORD="Welcome123456#"
WALLET_PASSWORD="WalletPass123#"
TNS_ALIAS="myatp_low"
IMAGE="container-registry.oracle.com/database/adb-free:latest-23ai"
CLEAN=false

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN=true ;;
    *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
  esac
  shift
done

command_exists() { command -v "$1" >/dev/null 2>&1; }

port_in_use() {
  local port="$1"
  if command_exists lsof; then
    lsof -i TCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  elif command_exists netstat; then
    netstat -an | grep -E "LISTEN|LISTENING" | grep -qE "[\.:]$port[ \)]"
    return $?
  else
    return 1
  fi
}

ensure_ports_free() {
  local ports=("$@")
  local conflict=false
  for p in "${ports[@]}"; do
    if port_in_use "$p"; then
      echo -e "${RED}❌ Port $p is in use. Close the process and retry.${NC}"
      conflict=true
    fi
  done
  if [ "$conflict" = true ]; then
    exit 1
  fi
}

cleanup() {
  docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
  rm -rf "$WALLET_DIR"
  mkdir -p "$WALLET_DIR"
}

pull_image() {
  echo -e "${YELLOW}📥 Pulling image...${NC}"
  docker pull "$IMAGE" && echo -e "${GREEN}✅ Pulled${NC}"
}

start_container() {
  echo -e "${YELLOW}🚀 Starting container...${NC}"
  ensure_ports_free 1521 1522 8443 27017
  docker run -d --name "$CONTAINER_NAME" \
    -p 1521:1521 -p 1522:1522 -p 8443:8443 -p 27017:27017 \
    -e WORKLOAD_TYPE=ATP \
    -e WALLET_PASSWORD="$WALLET_PASSWORD" \
    -e ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    --cap-add SYS_ADMIN \
    --device /dev/fuse \
    "$IMAGE"
}

wait_healthy() {
  echo -e "${YELLOW}⏳ Waiting for healthy (up to 10min)...${NC}"
  local tries=120
  while [ $tries -gt 0 ]; do
    state=$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo "unknown")
    if [ "$state" = "healthy" ]; then
      echo -e "${GREEN}✅ Healthy${NC}"
      return 0
    fi
    sleep 5
    tries=$((tries-1))
  done
  echo -e "${RED}❌ Not healthy. Logs:${NC}"
  docker logs "$CONTAINER_NAME"
  exit 1
}

copy_and_patch_wallet() {
  echo -e "${YELLOW}📂 Copying wallet...${NC}"
  docker cp "$CONTAINER_NAME:/u01/app/oracle/wallets/tls_wallet/." "$WALLET_DIR/"
  local tns="$WALLET_DIR/tnsnames.ora"
  if grep -q "myatp_low_tls" "$tns"; then
    echo -e "${GREEN}✅ Using myatp_low_tls${NC}"
    return 0
  fi
  echo -e "${YELLOW}🛠️ Appending $TNS_ALIAS...${NC}"
  local service_name
  service_name=$(sed -n '/myatp_low[[:space:]]*=/,/[)]/ { /service_name[[:space:]]*=/s/.*service_name[[:space:]]*=[[:space:]]*\([^)]*\).*/\1/p }' "$tns" || true)
  service_name="${service_name:-myatp_low.adb.oraclecloud.com}"
  cat >> "$tns" <<EOF

$TNS_ALIAS =
  (DESCRIPTION =
    (ADDRESS = (PROTOCOL = tcps)(PORT = 1522)(HOST = localhost))
    (CONNECT_DATA =
      (SERVICE_NAME = $service_name)
    )
    (SECURITY = (SSL_SERVER_DN_MATCH = FALSE))
  )
EOF
  echo -e "${GREEN}✅ Added (SERVICE_NAME=$service_name)${NC}"
}

check_docker() {
  echo -e "${YELLOW}🐳 Checking Docker...${NC}"
  if ! command_exists docker; then
    echo -e "${RED}❌ Docker is required. Install Docker Desktop for Mac.${NC}"
    exit 1
  fi
  echo -e "${GREEN}✅ Docker detected${NC}"
}

# Main
check_docker
if [ "$CLEAN" = true ]; then
  cleanup
fi
pull_image
start_container
wait_healthy
copy_and_patch_wallet

echo -e "${GREEN}🎉 DB ready! ${NC}"
