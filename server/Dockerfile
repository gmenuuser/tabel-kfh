FROM node:24.9.0-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY sql ./sql
COPY src ./src
# The volume of files and backups belongs to the user the server runs as.
RUN mkdir -p /data && chown node:node /data
USER node
EXPOSE 8080
CMD ["node", "src/main.ts"]
