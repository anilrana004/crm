# ---- build stage ----
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
# eclipse-temurin:25-jre already ships UID 1000 (ubuntu); reuse it instead of useradd
COPY --from=build /build/target/securetravels-crm-0.1.0.jar app.jar
RUN chown -R 1000:1000 /app
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]