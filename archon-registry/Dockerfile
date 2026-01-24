FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Copy the JAR file from the target directory
COPY target/archon-registry-1.0-SNAPSHOT.jar app.jar

# Expose port 8080
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
