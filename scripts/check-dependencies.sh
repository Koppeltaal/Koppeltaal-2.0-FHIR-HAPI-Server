#!/usr/bin/env bash
# Fails when the runtime jars of this build differ from the jars in the pinned starter WAR, apart from the exact
# exceptions in scripts/dependency-exceptions.txt. Run scripts/install-starter.sh first; it writes
# target/starter-war-lib.txt.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
[ -f target/starter-war-lib.txt ] || { echo "Run scripts/install-starter.sh first" >&2; exit 1; }

rm -rf target/resolved-libs
mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DincludeTypes=jar -DoutputDirectory=target/resolved-libs
ls target/resolved-libs | LC_ALL=C sort > target/resolved-lib.txt

LC_ALL=C comm -23 target/starter-war-lib.txt target/resolved-lib.txt | sed 's/^/war /' > target/dependency-diff.txt
LC_ALL=C comm -13 target/starter-war-lib.txt target/resolved-lib.txt | sed 's/^/ours /' >> target/dependency-diff.txt
grep -v '^#' scripts/dependency-exceptions.txt | grep -v '^$' | LC_ALL=C sort > target/dependency-expected.txt
if ! diff <(LC_ALL=C sort target/dependency-diff.txt) target/dependency-expected.txt; then
  echo "The jars differ from the starter WAR in other ways than scripts/dependency-exceptions.txt lists (< actual, > expected)"
  exit 1
fi
echo "Resolved jars match the starter WAR, apart from the listed exceptions"

# The extension jar must hold the auto-configuration and its imports file, and nothing of the starter.
ENTRIES="$("${JAVA_HOME:+$JAVA_HOME/bin/}jar" tf target/kt2-hapi-extension.jar)"
grep -qx "nl/koppeltaal/hapi/autoconfigure/KoppeltaalAutoConfiguration.class" <<< "$ENTRIES" \
  || { echo "the jar lacks KoppeltaalAutoConfiguration.class"; exit 1; }
grep -qx "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" <<< "$ENTRIES" \
  || { echo "the jar lacks the AutoConfiguration.imports file"; exit 1; }
grep -qx "nl/koppeltaal/hapi/extension.yaml" <<< "$ENTRIES" \
  || { echo "the jar lacks nl/koppeltaal/hapi/extension.yaml, which docker/config imports"; exit 1; }
grep -qx "META-INF/spring.factories" <<< "$ENTRIES" \
  || { echo "the jar lacks META-INF/spring.factories, which registers KoppeltaalActivationGuard"; exit 1; }
if grep -E "^ca/|application[^/]*\.ya?ml$" <<< "$ENTRIES"; then echo "starter classes or yaml in the jar"; exit 1; fi
echo "the jar holds the auto-configuration and only Koppeltaal classes"

# The image copies exactly these into /app/extra-classes, next to the extension jar. mvn package writes them, so
# run this after mvn verify.
[ -d target/extra-libs ] || { echo "target/extra-libs is missing; run mvn verify first" >&2; exit 1; }
EXTRAS="$(find target/extra-libs -mindepth 1 -maxdepth 1 -type f -name '*.jar' -exec basename {} \; | LC_ALL=C sort | tr '\n' ' ')"
ALL="$(ls -A target/extra-libs | wc -l | tr -d ' ')"
[ "$EXTRAS" = "java-jwt-4.5.0.jar jwks-rsa-0.22.1.jar lucene-backward-codecs-9.11.1.jar " ] && [ "$ALL" = "3" ] \
  || { echo "unexpected target/extra-libs: $(ls -A target/extra-libs | tr '\n' ' ')"; exit 1; }
echo "extra-libs holds exactly the three extras"
