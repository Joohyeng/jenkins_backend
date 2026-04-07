# 젠킨스에서 이미 빌드된 jar 파일을 복사만 해서 이미지를 만듭니다.
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl

# Jenkins 작업 공간(Workspace)에 있는 jar 파일을 이미지 안으로 복사
COPY build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]