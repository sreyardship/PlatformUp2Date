# The build output is static assets, the same for every target architecture.
# The build stage therefore runs on the build platform, and the runtime stage
# has no RUN step, so a multi-arch build needs no emulation (docs/adr/0043).
FROM --platform=$BUILDPLATFORM node:24-alpine AS build

WORKDIR /app

COPY package.json yarn.lock ./
RUN yarn install --frozen-lockfile

COPY . .
ARG BUILD_VERSION=dev
ARG BUILD_VERSION_REQUIRED=false
RUN BUILD_VERSION="$BUILD_VERSION" BUILD_VERSION_REQUIRED="$BUILD_VERSION_REQUIRED" yarn build
# The runtime config is written at container start, but the static files must
# stay read-only. Thus, the served path is a link into /tmp, the one writable
# path of the runtime image (docs/adr/0044). The link is made here, because
# the runtime stage has no RUN step (docs/adr/0043).
RUN ln -sf /tmp/env-config.js build/env-config.js

# The unprivileged nginx image runs as UID 101, listens on 8080, and keeps its
# pid file and temp paths in /tmp.
FROM nginxinc/nginx-unprivileged:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build /usr/share/nginx/html
COPY --chmod=755 docker-entrypoint.d/40-env-config.sh /docker-entrypoint.d/40-env-config.sh

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
