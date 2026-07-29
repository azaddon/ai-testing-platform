FROM mcr.microsoft.com/playwright/java:v1.45.0-jammy

WORKDIR /app

COPY target/*.jar app.jar

ENTRYPOINT ["java","-jar","app.jar"]