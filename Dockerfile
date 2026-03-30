# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/deadlock-analyzer-1.0-SNAPSHOT.jar ./deadlock-analyzer.jar
ENTRYPOINT ["java", "-jar", "deadlock-analyzer.jar"]
