FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="OMS Team"
LABEL service="oms-ingest"

WORKDIR /app

# Copy JAR
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

# Create non-root user
RUN addgroup -S oms && adduser -S oms -G oms
USER oms

# Environment variables
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication"
ENV SPRING_PROFILES_ACTIVE=prod

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080 9090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
