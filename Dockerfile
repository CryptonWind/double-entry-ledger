FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM build AS tests
CMD ["mvn", "--batch-mode", "--no-transfer-progress", "verify"]

FROM eclipse-temurin:17-jre AS application
WORKDIR /app
COPY --from=build /workspace/target/double-entry-ledger-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
