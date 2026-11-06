# Built from the repository root (see docker-compose.yml): the site is built first and served by the API.
FROM node:24.9.0-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web ./
RUN npx vite build

FROM node:24.9.0-alpine
WORKDIR /app
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev
COPY server/sql ./sql
COPY server/src ./src
COPY --from=web /web/dist ./web
ENV WEB_DIR=/app/web
# The volume of files and backups belongs to the user the server runs as.
RUN mkdir -p /data && chown node:node /data
USER node
EXPOSE 8080
CMD ["node", "src/main.ts"]
