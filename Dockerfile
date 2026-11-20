# The vanilla HAPI FHIR starter with the Koppeltaal extension jar on top. Only the WAR comes from the official image;
# the Java runtime is our own base image, so JRE fixes do not wait for a HAPI release.
FROM hapiproject/hapi:v8.2.0-2@sha256:df3ec210294f711ac0f89780c449ca843a450b5b5ca6c9a09f5f80509d9d07af AS hapi

FROM docker.io/library/maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY --from=hapi /app/main.war /tmp/main.war
COPY scripts/ scripts/
COPY pom.xml .
RUN scripts/install-starter.sh /tmp/main.war && mvn -B -ntp dependency:go-offline
COPY src/ src/
RUN mvn -B -ntp -DskipTests package

FROM gcr.io/distroless/java17-debian13:nonroot@sha256:5a09afc2ecb9453a60bd0f071a5e0b5e180cbafa23e53976a4e610b3d28f6288 AS default
USER 0
COPY certificates/notificaties.lsp.orangeclicks.nl.cer /tmp/subscriber.cer
RUN ["java", "sun.security.tools.keytool.Main", "-importcert", "-noprompt", "-cacerts", "-storepass", "changeit", \
     "-alias", "notificaties.lsp.orangeclicks.nl", "-file", "/tmp/subscriber.cer"]
USER 65532:65532
WORKDIR /app
COPY --from=hapi /app/main.war /app/opentelemetry-javaagent.jar /app/
COPY --from=build /build/target/kt2-hapi-extension.jar /app/extra-classes/
COPY --from=build /build/target/extra-libs/java-jwt-4.5.0.jar /build/target/extra-libs/jwks-rsa-0.22.1.jar \
     /build/target/extra-libs/lucene-backward-codecs-9.11.1.jar /app/extra-classes/
COPY docker/config/ /app/config/
ENV SPRING_CONFIG_LOCATION=file:/app/config/ \
    JDK_JAVA_OPTIONS="-Xms4g -Xmx8g -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof -XX:+ExitOnOutOfMemoryError -XX:NativeMemoryTracking=summary"
ENTRYPOINT ["java", "--class-path", "/app/main.war", "-Dloader.path=main.war!/WEB-INF/classes/,main.war!/WEB-INF/,/app/extra-classes", "org.springframework.boot.loader.PropertiesLauncher"]
