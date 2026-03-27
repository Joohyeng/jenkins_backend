# --- 1단계: 프론트엔드 빌드 ---
FROM node:lts-alpine AS frontend-builder
WORKDIR /app
COPY ./frontend/package*.json ./

RUN npm install
RUN npm install y-websocket

COPY ./frontend ./
RUN npm run build

# --- 2단계: 백엔드 빌드 ---
From gradle:8.14.4-jdk17 AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon

COPY ./src  ./src
COPY --from=frontend-builder /app/dist ./src/main/resources/static

# --- 3단계 : 실행 ---
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache nodejs npm

COPY --from=builder /app/build/libs/*.jar app.jar
RUN npm install -g y-websocket

EXPOSE 443 1234
CMD ["sh", "-c", "HOST=0.0.0.0 PORT=1234 npx y-websocket & java -jar app.jar"]
