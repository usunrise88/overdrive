# Build context is the repository root:
#   docker build -f server/Dockerfile -t overdrive-server .

FROM node:26-slim AS build
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
WORKDIR /repo

# Dependency layer: manifests only, so source edits do not invalidate it.
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.base.json ./
COPY contracts/package.json contracts/
COPY server/package.json server/
RUN pnpm install --frozen-lockfile

COPY contracts contracts
COPY server server
RUN pnpm --filter @overdrive/server build \
 && pnpm --filter @overdrive/server deploy --prod /out

FROM node:26-slim
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8080
WORKDIR /app
COPY --from=build /out/package.json ./
COPY --from=build /out/node_modules ./node_modules
COPY --from=build /out/dist ./dist
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/healthz').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"
CMD ["node", "dist/main.js"]
