FROM maven:3.6.3-jdk-11 AS build

ADD pom.xml /pom.xml
ADD src /src

RUN mvn clean package spring-boot:repackage -Pboot -DskipTests

FROM openjdk:11.0.10-jre


COPY --from=build target/ROOT.war /hapi-fhir-jpaserver.war

ENV TZ="Europe/Amsterdam"

EXPOSE 8080

ENV FHIR_PROFILE "R4"

ENTRYPOINT [ "sh", "-c", "java -jar -Dspring.profiles.active=$FHIR_PROFILE /hapi-fhir-jpaserver.war" ]
