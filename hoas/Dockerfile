# --- build: compile and package the Spring Boot jar ---
# Ubuntu 22.04 (jammy) based: the tools in the 24.04 images use system calls that "az acr build" hosts lack
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is reused unless pom.xml changes.
# Plain RUN steps only (no BuildKit cache mounts), so the image also builds with "az acr build".
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package \
	&& cp target/hoas-*.jar app.jar \
	&& java -Djarmode=tools -jar app.jar extract --destination extracted

# --- runtime: JRE only, non-root ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --no-create-home --uid 10001 hoas

# One COPY step: several consecutive "COPY --from" steps fail on the classic builder that "az acr build" uses
COPY --from=build /workspace/extracted/ ./

USER hoas
# The site shows dates and times in Finnish local time
ENV TZ=Europe/Helsinki
EXPOSE 8080

# Credentials are passed at runtime: HOAS_USERNAME and HOAS_PASSWORD (never baked into the image)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
