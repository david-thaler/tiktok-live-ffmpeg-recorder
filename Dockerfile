## Build the app
FROM maven:3.9-amazoncorretto-25 AS builder
LABEL authors="davidthaler"

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

## Runtime image
FROM amazoncorretto:25-alpine

# Install base dependencies
RUN apk add --no-cache tini python3 py3-pip ffmpeg bash ca-certificates curl \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --upgrade pip yt-dlp[default,curl-cffi] \
    && update-ca-certificates

# Make yt-dlp available in PATH
ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "app.jar"]