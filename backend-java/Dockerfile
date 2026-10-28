FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache sing-box tzdata
WORKDIR /app
COPY target/gpn-panel-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
