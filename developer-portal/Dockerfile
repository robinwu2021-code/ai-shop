FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY --chown=node:node . .
USER node
ENV NODE_ENV=production ADDRESS=0.0.0.0 PORT=3210
EXPOSE 3210
CMD ["node", "server.mjs"]
