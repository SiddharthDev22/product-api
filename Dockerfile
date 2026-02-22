FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/product-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
