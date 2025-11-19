#!/bin/bash
# run_app.sh - Starts backend and frontend with overrides (no project changes)
# Usage: ./run_app.sh [--backend-only]

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
WALLET_DIR="./adb_wallet"
ADMIN_PASSWORD="Welcome123456#"
BACKEND_ONLY=false

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend-only) BACKEND_ONLY=true ;;
    *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
  esac
  shift
done

start_backend() {
  echo -e "${YELLOW}🔧 Starting backend...${NC}"
  mkdir -p "$WALLET_DIR"
  cat > "$WALLET_DIR/local-override.yaml" <<EOF
spring:
  datasource:
    username: admin
    password: "$ADMIN_PASSWORD"
    type: com.zaxxer.hikari.HikariDataSource
    hikari:
      connection-test-query: SELECT 1 FROM DUAL
      idle-timeout: 30000
      max-lifetime: 60000
      connection-timeout: 30000
      keepalive-time: 10000
      leak-detection-threshold: 30000
logging:
  level:
    com.zaxxer.hikari: DEBUG
    org.hibernate.SQL: DEBUG
    com.oracle.jdbc: DEBUG
liquibase:
  enabled: true  # Migrations already run standalone
EOF

  pushd backend >/dev/null
  ./gradlew clean build
  export TNS_ADMIN="../$WALLET_DIR"
  SPRING_PROFILES_ACTIVE=local \
  SPRING_CONFIG_ADDITIONAL_LOCATION="file:../$WALLET_DIR/local-override.yaml" \
  ./gradlew bootRun > ../backend.log 2>&1 &
  BACKEND_PID=$!
  popd >/dev/null
}

wait_backend_health() {
  echo -e "${YELLOW}⏳ Waiting for backend health (up to 15min)...${NC}"
  local tries=180
  while [ $tries -gt 0 ]; do
    if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
      echo -e "${GREEN}✅ Backend is UP${NC}"
      return 0
    fi
    echo -e "${YELLOW}Checking (tries left: $tries)...${NC}"
    tail -n 5 backend.log || true
    sleep 5
    tries=$((tries-1))
  done
  echo -e "${RED}❌ Failed. Tail of backend.log:${NC}"
  tail -n 80 backend.log || true
  exit 1
}

start_frontend() {
  echo -e "${YELLOW}🌟 Starting frontend...${NC}"
  pushd app >/dev/null
  if [ ! -d node_modules ]; then
    npm ci
  fi
  npm run serve &
  FRONTEND_PID=$!
  popd >/dev/null
}

cleanup_on_exit() {
  [ ! -z "${BACKEND_PID:-}" ] && kill "$BACKEND_PID" 2>/dev/null || true
  [ ! -z "${FRONTEND_PID:-}" ] && kill "$FRONTEND_PID" 2>/dev/null || true
  rm -f "$WALLET_DIR/local-override.yaml"
  exit
}

# Main
trap cleanup_on_exit INT TERM
start_backend
wait_backend_health
if [ "$BACKEND_ONLY" != true ]; then
  start_frontend
fi

echo -e "${GREEN}🎉 App started! Backend: http://localhost:8080, Frontend: http://localhost:8000${NC}"
echo "Press Ctrl+C to stop"

wait
