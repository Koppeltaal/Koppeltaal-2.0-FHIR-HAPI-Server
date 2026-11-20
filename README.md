# Koppeltaal 2.0 FHIR server

The Koppeltaal extension for the unmodified [HAPI FHIR JPA starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter).
This repository holds only Koppeltaal code and configuration:

- **Code:** `src/main/java/nl/koppeltaal/hapi` is one jar. The starter loads it from `/app/extra-classes` through the
  Spring Boot auto-configuration `KoppeltaalAutoConfiguration`.
- **Configuration:** `docker/config/` holds the configuration; the image restricts Spring to it
  (`SPRING_CONFIG_LOCATION`).
- **Image:** the `Dockerfile` copies the starter's WAR out of the pinned `hapiproject/hapi` image onto our own
  distroless Java base image.

## Build and test

```bash
scripts/install-starter.sh   # once per starter version: the starter's classes, for the tests that boot it
mvn verify
scripts/check-dependencies.sh
./e2e/smoke.sh               # builds the image and starts it on a fresh database
```

## Upgrade HAPI

1. Bump the `hapiproject/hapi` tag and digest in the `Dockerfile`, and `hapi.starter.image.version` and the parent in
   `pom.xml`.
2. Run `scripts/install-starter.sh`, then align `pom.xml` with the starter's embedded pom until
   `scripts/check-dependencies.sh` passes. Update `scripts/dependency-exceptions.txt` only for reviewed differences.
3. Diff the starter's `application.yaml` with the previous version, and carry over or switch off new settings in
   `docker/config/`.
4. Fix the Koppeltaal code where HAPI internals changed, then run `mvn verify` and the smoke test.
