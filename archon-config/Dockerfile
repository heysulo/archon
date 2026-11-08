FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY target/archon-config-1.0-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
