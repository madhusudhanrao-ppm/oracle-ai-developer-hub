FROM nginx:1.23-alpine-slim

RUN rm -rf /usr/share/nginx/html/*

COPY web/ /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
