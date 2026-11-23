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

- **From Maven, for development:** `mvn spring-boot:run` starts the starter's `Application` with this extension and `docker/config/`, without building an image. It needs JDK 17 and the starter classes from `scripts/install-starter.sh`. It connects to the datasource in `docker/config/application.yaml` (`127.0.0.1:5432/hapi_fhir_server_dev`); override that with environment variables such as `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME`. Security is off in that configuration; `FHIR_SERVER_SECURITY_ENABLED=true` switches the token checks on. For a debugger, add `-Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"`.
- **In Docker:** `docker compose up --build` starts the image with a Postgres container on host port 5432. That container has no volume, so `docker compose down` discards its data.

The FHIR base is `http://localhost:8080/fhir/DEFAULT` in both cases.

## Runtime settings

- **JVM options:** the image sets `JDK_JAVA_OPTIONS` (heap, GC log, heap dump). Setting `JDK_JAVA_OPTIONS` in an environment replaces all of them. `JAVA_TOOL_OPTIONS` adds flags; for a flag that both set, `JDK_JAVA_OPTIONS` wins.
- **Replaced starter settings:** Koppeltaal replaces four starter features with its own, each under its own key. `koppeltaal.cascading-deletes` replaces `hapi.fhir.allow_cascading_deletes`, `koppeltaal.cors` replaces `hapi.fhir.cors`, `koppeltaal.repository-validation` replaces `hapi.fhir.enable_repository_validating_interceptor`, and `koppeltaal.implementationguides` replaces `hapi.fhir.implementationguides`, with the same keys per guide, installed in configuration order. The starter keys must not be set anywhere: the server refuses to start when a configuration file, an environment variable, `SPRING_APPLICATION_JSON`, a system property or a command-line argument sets one, whatever its value, and names each setting with where it comes from. An environment therefore sets `KOPPELTAAL_IMPLEMENTATIONGUIDES_KT2_VERSION`, not `HAPI_FHIR_IMPLEMENTATIONGUIDES_KT2_VERSION`.
- **Extension guards:** the server starts only with an active extension. Without the jar, the import of `nl/koppeltaal/hapi/extension.yaml` in `docker/config/application.yaml` fails. With the jar, `KoppeltaalActivationGuard` refuses a switched-off or excluded `KoppeltaalAutoConfiguration` before the application context starts. The `KoppeltaalExtensionPresent` marker in `hapi.fhir.custom-interceptor-classes` is a third check; an override of that list replaces it, so keep the marker in it.

## Upgrade HAPI

1. Bump the `hapiproject/hapi` tag and digest in the `Dockerfile`, and `hapi.starter.image.version` and the parent in `pom.xml`.
2. Run `scripts/install-starter.sh`, then align `pom.xml` with the starter's embedded pom until `scripts/check-dependencies.sh` passes. Update `scripts/dependency-exceptions.txt` only for reviewed differences. The versions of the three extra jars also appear in the `Dockerfile` and in `scripts/check-dependencies.sh`.
3. Diff the starter's `application.yaml` with the previous version, and carry over or switch off new settings in `docker/config/`.
4. Fix the Koppeltaal code where HAPI internals changed, then run `mvn verify` and the smoke test. Where the upgrade changes the wiring on purpose, regenerate the contract snapshots and review their diff.
