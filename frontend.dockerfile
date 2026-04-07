# --- 1단계: 빌드 (Node.js) ---
FROM node:lts-alpine AS build-stage
WORKDIR /app

# 1. 패키지 파일 복사 (루트 기준 frontend 폴더 내부의 것을 복사)
COPY frontend/package*.json ./
RUN npm install

# 2. 나머지 소스 코드 복사 (중요: frontend 폴더 '안의 내용'을 /app으로 복사)
COPY frontend/ ./

# 3. 빌드 실행
RUN npm run build

# --- 2단계: 실행 (Nginx) ---
FROM nginx:stable-alpine

ARG BACKEND_UPSTREAM=backend-app:8080
ARG REALTIME_UPSTREAM=websocket-server:1234

# 빌드된 정적 파일 복사 (보통 빌드 결과물은 dist 또는 build 폴더에 생깁니다)
# Vite/Vue라면 dist, React라면 build일 확률이 높으니 확인해 보세요.
COPY --from=build-stage /app/dist /usr/share/nginx/html

# Nginx 설정 (기존과 동일)
RUN printf "server {\n\
    listen 80;\n\
\n\
    location / {\n\
        root /usr/share/nginx/html;\n\
        index index.html;\n\
        try_files \$uri \$uri/ /index.html;\n\
    }\n\
\n\
    location /wss {\n\
        proxy_pass http://$REALTIME_UPSTREAM;\n\
        proxy_http_version 1.1;\n\
        proxy_set_header Upgrade \$http_upgrade;\n\
        proxy_set_header Connection \"upgrade\";\n\
        proxy_set_header Host \$host;\n\
        proxy_set_header X-Real-IP \$remote_addr;\n\
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;\n\
        proxy_set_header X-Forwarded-Proto \$scheme;\n\
    }\n\
\n\
    location /api/ws-stomp {\n\
        proxy_pass http://$REALTIME_UPSTREAM;\n\
        proxy_http_version 1.1;\n\
        proxy_set_header Upgrade \$http_upgrade;\n\
        proxy_set_header Connection \"upgrade\";\n\
        proxy_set_header Host \$host;\n\
        proxy_set_header X-Real-IP \$remote_addr;\n\
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;\n\
        proxy_set_header X-Forwarded-Proto \$scheme;\n\
    }\n\
\n\
    location /api/sse {\n\
        proxy_pass http://$REALTIME_UPSTREAM;\n\
        proxy_http_version 1.1;\n\
        proxy_buffering off;\n\
        proxy_cache off;\n\
        proxy_read_timeout 3600s;\n\
        proxy_set_header Host \$host;\n\
        proxy_set_header X-Real-IP \$remote_addr;\n\
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;\n\
        proxy_set_header X-Forwarded-Proto \$scheme;\n\
    }\n\
\n\
    location /api {\n\
        proxy_pass http://$BACKEND_UPSTREAM;\n\
        proxy_set_header Host \$host;\n\
        proxy_set_header X-Real-IP \$remote_addr;\n\
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;\n\
    }\n\
}\n" > /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
