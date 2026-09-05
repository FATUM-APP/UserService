FROM eclipse-temurin:21-jre

WORKDIR /app

ENV PORT=8080

COPY target/user-service-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
