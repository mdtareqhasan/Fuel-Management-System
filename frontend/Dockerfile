# Multi-stage build for the React frontend.
# Stage 1 builds the static bundle with a locked dependency tree.
# Stage 2 serves it with Nginx, which also proxies /api to the backend so the
# browser talks to a single origin and CORS never enters the picture.

FROM node:22-alpine AS build
WORKDIR /build

# Copy the lockfile first so the dependency layer is cached independently of source.
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY tsconfig.json vite.config.ts index.html ./
COPY src/ src/
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://localhost/ >/dev/null || exit 1

CMD ["nginx", "-g", "daemon off;"]
