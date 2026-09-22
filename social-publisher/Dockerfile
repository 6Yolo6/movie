FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends ca-certificates openssl chromium \
    && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g tencent-channel-cli@1.0.10

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY index.mjs security.mjs ./
COPY weibo-web.mjs ./
COPY weibo-login.mjs ./
COPY weibo-sso-login.mjs ./

RUN mkdir -p /data/qq-accounts /data/weibo && chown -R node:node /app /data
USER node
EXPOSE 8093
CMD ["node", "index.mjs"]
