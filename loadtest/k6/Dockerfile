FROM grafana/k6:1.3.0
USER root
RUN apk add --no-cache python3
WORKDIR /scripts
COPY run.js ./
COPY lib ./lib
COPY scenarios ./scenarios
COPY gen-tokens.py entrypoint.sh ./
USER 12345
ENTRYPOINT ["sh", "/scripts/entrypoint.sh"]
CMD ["/scripts/run.js"]
