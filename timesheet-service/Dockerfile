FROM eclipse-temurin:21-jre

WORKDIR /app

ARG JAR_FILE=target/timesheet-service-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8086

ENTRYPOINT ["java", "-jar", "app.jar"]
