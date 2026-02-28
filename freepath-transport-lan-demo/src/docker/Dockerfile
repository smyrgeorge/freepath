FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# The fat JAR is built locally by run.sh before this image is assembled.
# Build context is the project root, so the path below resolves correctly.
COPY freepath-transport-lan-demo/build/libs/*-fat.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
