# backend

Spring Boot backend of the homelab inbox (brief H-023, plan `docs/architecture/inbox-split.md`). It runs beside the
Python inbox; nothing is switched over to it yet. For now it only has `GET /api/backend/health`.

- Java 21, Spring Boot 4.1.2 (the parent POM pins all plugin and dependency versions), Maven 3.9.16 through the
  wrapper (`mvnw`, version and checksum in `.mvn/wrapper/maven-wrapper.properties`).
- Dependencies: web, JDBC, Flyway, pgjdbc and the test starter. The datasource and Flyway are switched off in
  `application.properties` until the database tasks (B2, B4) add them.

## Build and test

Needs a JDK 21 (on the box: `openjdk-21-jdk-headless`; CI uses setup-java).

```sh
cd backend
./mvnw verify
```

## Run

```sh
./mvnw spring-boot:run          # or: java -jar target/inbox-backend-0.1.0.jar
curl http://127.0.0.1:8130/api/backend/health
# {"status":"ok","service":"homelab-inbox-backend"}
```

It binds to loopback only. `BACKEND_HOST` (default `127.0.0.1`) and `BACKEND_PORT` (default `8130`) override this; the
install task (B3) records the real port in `config/inbox.conf`. Never set `BACKEND_HOST` to anything but a loopback
address.

## Changing versions

Raise the parent version in `pom.xml`, or the Maven version and its `distributionSha256Sum` in the wrapper properties,
in a PR of its own; `./mvnw verify` must stay green.
