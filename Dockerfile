# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source for faster rebuilds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Import corporate internal CA so the JVM trusts internal endpoints like
# litellm.ai.netcracker.cloud (container images don't ship your org's CA by default)
COPY certs/corporate-ca.crt.cer /tmp/corporate-ca.crt
RUN keytool -importcert -trustcacerts -noprompt \
    -alias corporate-ca \
    -file /tmp/corporate-ca.crt \
    -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit \
    && rm /tmp/corporate-ca.crt

COPY --from=build /build/target/rag-project-1.0.0.jar app.jar

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
