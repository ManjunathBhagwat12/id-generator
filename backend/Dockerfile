FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests need Docker (Testcontainers) so they are run separately: see README.
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/id-generator.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
