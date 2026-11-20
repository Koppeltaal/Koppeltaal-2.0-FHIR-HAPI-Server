#!/usr/bin/env bash
# Installs the classes of the pinned HAPI starter WAR in the local Maven repository as
# nl.koppeltaal.vendor:hapi-fhir-jpaserver-starter-classes:<version>, for the tests that boot the starter, and writes
# the WAR's library list to target/starter-war-lib.txt for scripts/check-dependencies.sh.
#
# usage: scripts/install-starter.sh [path/to/main.war]
#   Without an argument it copies /app/main.war out of the hapiproject/hapi image the Dockerfile pins (by digest).
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
WORK="$(mktemp -d)"
CONTAINER=""
cleanup() {
  rm -rf "$WORK"
  if [ -n "$CONTAINER" ]; then docker rm -f "$CONTAINER" > /dev/null 2>&1 || true; fi
}
trap cleanup EXIT

WAR="${1:-}"
if [ -z "$WAR" ]; then
  IMAGE="$(sed -nE 's/^FROM (hapiproject\/hapi:[^ ]+@sha256:[0-9a-f]{64}) AS hapi$/\1/p' "$ROOT_DIR/Dockerfile")"
  [ -n "$IMAGE" ] || { echo "No digest-pinned 'FROM hapiproject/hapi:...@sha256:... AS hapi' line in the Dockerfile" >&2; exit 1; }
  CONTAINER="$(docker create "$IMAGE")"
  docker cp "$CONTAINER:/app/main.war" "$WORK/main.war" > /dev/null
  WAR="$WORK/main.war"
fi
WAR="$(cd "$(dirname "$WAR")" && pwd)/$(basename "$WAR")"

mkdir -p "$WORK/war" "$ROOT_DIR/target"
(cd "$WORK/war" && "$JAR" xf "$WAR" WEB-INF/classes META-INF/maven)
VERSION="$(sed -n 's/^version=//p' "$WORK/war/META-INF/maven/ca.uhn.hapi.fhir/hapi-fhir-jpaserver-starter/pom.properties")"
# The starter's own configuration is never read: the image restricts Spring to /app/config (docker/config).
rm -f "$WORK/war/WEB-INF/classes/application.yaml"
"$JAR" --create --file "$WORK/starter-classes.jar" -C "$WORK/war/WEB-INF/classes" .
"$JAR" tf "$WAR" | sed -n 's#^WEB-INF/lib/\([^/]*\.jar\)$#\1#p' | LC_ALL=C sort > "$ROOT_DIR/target/starter-war-lib.txt"

(cd "$WORK" && mvn -B -q install:install-file -Dfile="$WORK/starter-classes.jar" -DgroupId=nl.koppeltaal.vendor \
  -DartifactId=hapi-fhir-jpaserver-starter-classes -Dversion="$VERSION" -Dpackaging=jar -DgeneratePom=true)
echo "Installed nl.koppeltaal.vendor:hapi-fhir-jpaserver-starter-classes:$VERSION from $WAR"
