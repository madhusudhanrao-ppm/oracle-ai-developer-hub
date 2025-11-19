#!/bin/bash
# migrate_db.sh - Runs Liquibase migrations using Docker, mounting project changelog and wallet
# Usage: ./migrate_db.sh

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
WALLET_DIR="./adb_wallet"
ADMIN_PASSWORD="Welcome123456#"
TNS_ALIAS="myatp_low"
LIQUIBASE_IMAGE="liquibase/liquibase:latest"
TEMP_CLASSPATH="./temp_classpath"

# Main
PROJECT_ROOT=$(dirname "$(pwd)")
echo -e "${YELLOW}🛠️ Preparing classpath for Oracle drivers...${NC}"
rm -rf "$TEMP_CLASSPATH"
mkdir -p "$TEMP_CLASSPATH"

copy_jar() {
  local pattern="$1"
  local jar_path=$(find ~/.gradle/caches -name "$pattern" ! -name "*javadoc*" ! -name "*sources*" -print -quit 2>/dev/null)
  if [ -z "$jar_path" ]; then
    echo -e "${RED}❌ No jar matching $pattern found in Gradle cache. Run ./gradlew build in backend/ to download dependencies.${NC}"
    exit 1
  fi
  cp "$jar_path" "$TEMP_CLASSPATH/"
  echo -e "${GREEN}✅ Copied $(basename "$jar_path")${NC}"
}

copy_jar "ojdbc11-*.jar"
copy_jar "ucp-*.jar"
copy_jar "oraclepki-*.jar"
copy_jar "osdt_cert-*.jar"
copy_jar "osdt_core-*.jar"

CLASSPATH_STR=""
for file in "$TEMP_CLASSPATH"/*.jar; do
  CLASSPATH_STR+="/liquibase/classpath/$(basename "$file"):"
done
CLASSPATH_STR=${CLASSPATH_STR%:}
echo -e "${YELLOW}🛠️ Running Liquibase migrations with classpath: $CLASSPATH_STR...${NC}"
docker run --rm --network host \
  -v "$PROJECT_ROOT/backend/src/main/resources/db:/liquibase/changelog" \
  -v "$PROJECT_ROOT/adb_wallet:/wallet" \
  -v "$(pwd)/$TEMP_CLASSPATH:/liquibase/classpath" \
  "$LIQUIBASE_IMAGE" /bin/sh -c "\
    cp \$JAVA_HOME/lib/security/cacerts /tmp/cacerts && \
    keytool -import -noprompt -trustcacerts -alias adb_cert \
    -file /wallet/adb_container.cert \
    -keystore /tmp/cacerts \
    -storepass changeit && \
    java -cp '/liquibase/internal/lib/*:/liquibase/classpath/*' \
    -Djavax.net.ssl.trustStore=/tmp/cacerts \
    -Djavax.net.ssl.trustStorePassword=changeit \
    liquibase.integration.commandline.LiquibaseCommandLine \
    --log-level=debug \
    --url='jdbc:oracle:thin:@$TNS_ALIAS?TNS_ADMIN=/wallet' \
    --username=admin \
    --password='$ADMIN_PASSWORD' \
    --changeLogFile=changelog/db.changelog-master.yaml \
    update \
    --classpath='$CLASSPATH_STR'"

rm -rf "$TEMP_CLASSPATH"
echo -e "${GREEN}✅ Migrations complete! Run run_app.sh to start the app.${NC}"
