# Koppeltaal 2.0 FHIR server

The Koppeltaal extension for the unmodified [HAPI FHIR JPA starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter). This repository holds only Koppeltaal code and configuration:

- **Code:** `src/main/java/nl/koppeltaal/hapi` is one jar. The starter loads it from `/app/extra-classes` through the Spring Boot auto-configuration `KoppeltaalAutoConfiguration`.
- **Configuration:** `docker/config/` holds the configuration; the image restricts Spring to it (`SPRING_CONFIG_LOCATION`).
- **Image:** the `Dockerfile` copies the starter's WAR out of the pinned `hapiproject/hapi` image onto our own distroless Java base image.

## Build and test

```bash
scripts/install-starter.sh   # the starter's classes for the tests, and target/starter-war-lib.txt; run it again after mvn clean
mvn verify
scripts/check-dependencies.sh
./e2e/smoke.sh               # builds the image and starts it on a fresh database
```

`ServerWiringContractTest` pins the server's wiring in `src/test/resources/contract`. When a change to the wiring is intended, regenerate the snapshots with `mvn test -Dtest=ServerWiringContractTest -Dcontract.update=true` and review their diff.

## Run locally

`docker compose up --build` starts the server with a fresh Postgres. The FHIR base is `http://localhost:8080/fhir/DEFAULT`.

## Runtime settings

- **JVM options:** the image sets `JDK_JAVA_OPTIONS` (heap, GC log, heap dump). Setting `JDK_JAVA_OPTIONS` in an environment replaces all of them. `JAVA_TOOL_OPTIONS` adds flags; for a flag that both set, `JDK_JAVA_OPTIONS` wins.
- **Extension guards:** the server does not start without the extension jar. `docker/config/application.yaml` imports `nl/koppeltaal/hapi/extension.yaml` from the jar, and `hapi.fhir.custom-interceptor-classes` names the `KoppeltaalExtensionPresent` marker, which also stops a server whose auto-configuration is switched off. An override of that list replaces it, so keep the marker in it.

## Upgrade HAPI

1. Bump the `hapiproject/hapi` tag and digest in the `Dockerfile`, and `hapi.starter.image.version` and the parent in `pom.xml`.
2. Run `scripts/install-starter.sh`, then align `pom.xml` with the starter's embedded pom until `scripts/check-dependencies.sh` passes. Update `scripts/dependency-exceptions.txt` only for reviewed differences. The versions of the three extra jars also appear in the `Dockerfile` and in `scripts/check-dependencies.sh`.
3. Diff the starter's `application.yaml` with the previous version, and carry over or switch off new settings in `docker/config/`.
4. Fix the Koppeltaal code where HAPI internals changed, then run `mvn verify` and the smoke test. Where the upgrade changes the wiring on purpose, regenerate the contract snapshots and review their diff.
