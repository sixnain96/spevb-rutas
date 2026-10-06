FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system spevb && useradd --system --gid spevb spevb
COPY --from=build /app/target/spevb-rutas-*.jar /app/app.jar
USER spevb
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
