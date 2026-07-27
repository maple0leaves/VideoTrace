# ---- 构建阶段 ----
ARG DOCKER_HUB_PROXY=m.daocloud.io/docker.io/library
FROM ${DOCKER_HUB_PROXY}/node:22-alpine AS builder
WORKDIR /app

ARG NPM_REGISTRY=https://repo.huaweicloud.com/repository/npm/

COPY package*.json ./
RUN npm config set registry "${NPM_REGISTRY}" && \
    npm ci --no-audit --prefer-offline

COPY . .

# 接收 API 地址构建参数，默认指向宿主机 9090 端口
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

# ---- 运行阶段 ----
FROM ${DOCKER_HUB_PROXY}/nginx:stable-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
