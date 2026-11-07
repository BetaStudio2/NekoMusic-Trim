# =========================
# 前端构建阶段
# =========================
FROM node:22-bookworm-slim AS frontend

ARG NEKO_WEB_VERSION=dev
ENV NEKO_WEB_VERSION=${NEKO_WEB_VERSION}

WORKDIR /frontend

COPY frontend/package.json ./
# 镜像自带的 npm 10.9.x 在解析当前依赖的 peer 树时会触发 edgesOut 崩溃。
RUN npm install -g npm@11.19.1 \
    && npm install --no-audit --no-fund

COPY frontend ./
RUN npm run build -- --outDir /site


# =========================
# 构建阶段
# =========================
FROM maven:3.10.0-eclipse-temurin-27 AS builder

WORKDIR /build

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates cmake curl xz-utils \
    && rm -rf /var/lib/apt/lists/*

COPY backend/pom.xml .
COPY backend/scripts ./scripts
COPY backend/native ./native
COPY backend/src ./src
COPY --from=frontend /site ./src/main/resources/site

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests -Dneko.frontend.skip=true


# =========================
# JDK 下载阶段
# =========================
# Azul 尚未发布 Zulu 27.30.107 的可拉取制品，暂固定到已包含 OpenJDK 27.0.1
# 及后续紧急安全修复的 Liberica 27.0.1.0.2+5；Zulu 发布后可替换此阶段。
FROM debian:13-slim AS jdk

ARG LIBERICA_VERSION=27.0.1.0.2+5
ARG LIBERICA_SHA256=5a1f69d9997c15769e9408a26e511110ce599977c927e57d7c8bfa928a64b4a1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && JDK_FILE="bellsoft-jdk${LIBERICA_VERSION}-linux-amd64-lite.tar.gz" \
    && (curl -fL --http1.1 --retry 10 --retry-all-errors --retry-delay 5 \
            --connect-timeout 30 --speed-limit 1024 --speed-time 30 -C - \
            "https://download.bell-sw.com/java/${LIBERICA_VERSION}/${JDK_FILE}" \
            -o /tmp/jdk.tar.gz \
        || { rm -f /tmp/jdk.tar.gz; \
             curl -fL --http1.1 --retry 10 --retry-all-errors --retry-delay 5 \
                 --connect-timeout 30 --speed-limit 1024 --speed-time 30 -C - \
                 "https://github.com/bell-sw/Liberica/releases/download/${LIBERICA_VERSION}/${JDK_FILE}" \
                 -o /tmp/jdk.tar.gz; }) \
    && echo "${LIBERICA_SHA256}  /tmp/jdk.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/java \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1 \
    && rm /tmp/jdk.tar.gz \
    && /opt/java/bin/java -version 2>&1 | grep -F '27.0.1.0.2'


# =========================
# 运行阶段
# =========================
FROM debian:13-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=jdk /opt/java /opt/java
COPY --from=builder /build/target/backend-1.0-SNAPSHOT.jar /app/backend.jar

EXPOSE 65535

ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:${PATH} \
    LANG=C.UTF-8 \
    LANGUAGE=C.UTF-8 \
    LC_ALL=C.UTF-8

# JVM 参数说明（改动前先读）：
#   MaxGCPauseMillis 是 G1 的**停顿目标**，不是上限。取 100 等于告诉 G1「100ms 可以接受」，
#   于是它会一路把年轻代养大、直到单次停顿贴近该值——访问日志里那些原本该 <5ms 的接口
#   （favorites / userinfo / media 等）零散出现 100~106ms，就是卡在这条线上。
#   降到 25 会让 G1 缩小年轻代、用更频繁但更短的停顿替代。
#   UseStringDeduplication 在每次年轻代回收里多扫一遍存活字符串，对以文件响应为主、堆里
#   字符串周转量不大的服务只增加停顿时间，不省内存，故去掉。
#   若要彻底消除这类停顿，可改用 ZGC（-XX:+UseZGC）并把下面所有 G1 专属参数一并删除。
ENTRYPOINT ["java", "-server", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=25", \
    "-XX:InitialRAMPercentage=25.0", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+DisableExplicitGC", \
    "-Dfile.encoding=UTF-8", \
    "-Dsun.jnu.encoding=UTF-8", \
    "-Djava.awt.headless=true", \
    "-jar", "/app/backend.jar"]
