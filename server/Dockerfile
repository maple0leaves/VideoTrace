# ---- 构建阶段 ----
ARG DOCKER_HUB_PROXY=m.daocloud.io/docker.io/library
FROM ${DOCKER_HUB_PROXY}/eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# 利用本地 .m2repo 缓存加速构建
COPY .mvn .mvn
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw && ./mvnw -s .mvn/settings.xml -B -DskipTests dependency:go-offline

COPY src src

RUN ./mvnw -s .mvn/settings.xml -B package

# ---- 运行阶段 ----
FROM ${DOCKER_HUB_PROXY}/eclipse-temurin:21-jre-jammy
WORKDIR /app

ARG UBUNTU_MIRROR=https://repo.huaweicloud.com/ubuntu
ARG PYPI_INDEX_URL=https://repo.huaweicloud.com/repository/pypi/simple

# 安装 FFmpeg、Tesseract（含中英文语言包）
RUN sed -i \
        -e "s|http://archive.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        -e "s|http://security.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        /etc/apt/sources.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ffmpeg \
        python3 \
        python3-venv \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        tesseract-ocr-eng && \
    python3 -m venv /opt/yt-dlp && \
    /opt/yt-dlp/bin/pip install \
        --no-cache-dir \
        --index-url "${PYPI_INDEX_URL}" \
        --timeout 60 \
        --retries 3 \
        yt-dlp && \
    ln -s /opt/yt-dlp/bin/yt-dlp /usr/local/bin/yt-dlp && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/*.jar app.jar
COPY default-media default-media

RUN groupadd --system --gid 10001 appuser && \
    useradd --system --uid 10001 --gid 10001 --create-home appuser && \
    mkdir -p /tmp/vidotrace && \
    chown 10001:10001 /tmp/vidotrace
USER appuser

EXPOSE 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
