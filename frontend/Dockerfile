FROM node:24-alpine AS build
WORKDIR /web
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_BACKEND=python
ENV VITE_BACKEND=$VITE_BACKEND
RUN npm run build
FROM nginx:1.28-alpine
COPY --from=build /web/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
