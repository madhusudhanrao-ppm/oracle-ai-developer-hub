ARG PLATFORM=linux/amd64

FROM --platform=$PLATFORM node:18-slim AS builder

WORKDIR /usr/src/app
RUN mkdir -p ./src 
RUN mkdir -p ./scripts 

COPY src/ ./src/
COPY scripts/ ./scripts/
COPY *.json ./


RUN npm install -g @oracle/ojet-cli@19.0.0
RUN npm install
RUN ojet build web --release

FROM --platform=$PLATFORM nginx:1.23-alpine-slim
RUN rm -rf /usr/share/nginx/html/*
COPY --from=builder /usr/src/app/web/ /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]