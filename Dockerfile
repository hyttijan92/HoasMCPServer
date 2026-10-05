# syntax=docker/dockerfile:1

# --- build: compile and package the Spring Boot jar ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached unless pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
	&& cp target/hoas-*.jar app.jar \
	&& java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- runtime: JRE only, non-root ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home --uid 10001 hoas

# Layers ordered from least to most frequently changing
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER hoas
# The site shows dates and times in Finnish local time
ENV TZ=Europe/Helsinki
EXPOSE 8080

# Credentials are passed at runtime: HOAS_USERNAME and HOAS_PASSWORD (never baked into the image)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
