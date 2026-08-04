FROM node:24.14.0-alpine@sha256:7fddd9ddeae8196abf4a3ef2de34e11f7b1a722119f91f28ddf1e99dcafdf114 AS build
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY web-console/package.json web-console/package.json
RUN corepack enable && pnpm install --frozen-lockfile
COPY web-console web-console
RUN pnpm --filter @workflow-platform/web-console build
FROM nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de
COPY --from=build /workspace/web-console/dist /usr/share/nginx/html
EXPOSE 80
