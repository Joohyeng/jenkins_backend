FROM node:20-alpine
WORKDIR /app

COPY websocket-server/package*.json ./
RUN npm install --legacy-peer-deps

COPY websocket-server/server.js ./server.js

ENV HOST=0.0.0.0
ENV PORT=1234
ENV REDIS_HOST=redis
ENV REDIS_PORT=6379
EXPOSE 1234

CMD ["npm", "start"]
