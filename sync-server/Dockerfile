FROM node:22-alpine
WORKDIR /app
COPY server.js .
ENV PORT=8080 DATA_DIR=/data
VOLUME /data
EXPOSE 8080
USER node
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["node", "server.js"]
