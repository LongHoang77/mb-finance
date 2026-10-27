# ---- Build dashboard ----
FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- Build server (better-sqlite3 cần công cụ build nếu không có bản dựng sẵn cho ARM64) ----
FROM node:22-bookworm-slim AS server
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY server/package.json server/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY server/ ./
RUN npm run build && npm prune --omit=dev

# ---- Runtime ----
FROM node:22-bookworm-slim
ARG GIT_SHA=dev
ENV APP_VERSION=$GIT_SHA \
    NODE_ENV=production \
    PORT=8080 \
    DB_FILE=/data/finance.db \
    WEB_DIR=/app/public
WORKDIR /app
RUN mkdir -p /data && chown node:node /data
COPY --from=server /app/package.json ./
COPY --from=server /app/node_modules ./node_modules
COPY --from=server /app/dist ./dist
COPY --from=server /app/migrations ./migrations
COPY --from=web /web/dist ./public
USER node
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --retries=3 --start-period=30s --start-interval=3s \
  CMD node -e "fetch('http://127.0.0.1:8080/api/v1/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/index.js"]
