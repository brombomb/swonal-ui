FROM node:22-slim

RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY prisma ./prisma
RUN npx prisma generate

COPY tsconfig.json tsconfig.server.json vite.config.ts tailwind.config.js postcss.config.js ./
COPY src ./src

RUN npm run build && mkdir -p /data

ENV NODE_ENV=production
ENV PORT=3000
ENV DATABASE_URL="file:/data/swonal.db"

EXPOSE 3000

CMD ["sh", "-c", "npx prisma db push --skip-generate && node dist/server/index.js"]
