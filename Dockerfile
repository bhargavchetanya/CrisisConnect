# ---------- CrisisConnect web portal + REST API (cloud deployment) ----------
# Stage 1: build with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Package the app and copy only runtime libraries (JavaFX is not needed on the server)
RUN mvn -q -B -DskipTests package dependency:copy-dependencies \
        -DincludeScope=runtime -DexcludeGroupIds=org.openjfx

# Stage 2: small runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/crisisconnect-1.0.0.jar app.jar
COPY --from=build /app/target/dependency ./lib
COPY website ./website
COPY config ./config
EXPOSE 8080
# Cloud hosts (Render, Railway, ...) pass the port in $PORT; locally it falls back to 8080
CMD ["sh", "-c", "java -XX:MaxRAMPercentage=75 -Dapi.port=${PORT:-8080} -cp 'app.jar:lib/*' com.crisisconnect.api.ApiServerMain"]
