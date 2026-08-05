FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates openssl \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g tencent-channel-cli@1.0.10 @weibo-ai/weibo-cli@0.9.0

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY index.mjs ./

EXPOSE 8093
CMD ["node", "index.mjs"]
