# The Server in a container. Expects dist/kinuvo.jar from `scripts/package.sh jar`.
#   docker run -d -p 127.0.0.1:8787:8787 -v kinuvo:/data ghcr.io/feernandooff/kinuvo
#   docker exec -it <container> kinuvo pair --manage
# Behind a proxy container (Coolify, Dokploy, ...): set the domain there, don't publish 8787; see docker-compose.yml.
FROM eclipse-temurin:25-jre

RUN useradd --system --uid 10001 --home-dir /data kinuvo \
    && mkdir /data && chown kinuvo /data \
    && printf '#!/bin/sh\nexec java $KINUVO_JAVA_OPTS -jar /opt/kinuvo/kinuvo.jar "$@"\n' > /usr/local/bin/kinuvo \
    && chmod +x /usr/local/bin/kinuvo
COPY dist/kinuvo.jar /opt/kinuvo/kinuvo.jar

USER kinuvo
ENV KINUVO_HOME=/data
ENV KINUVO_BEHIND_PROXY=1
VOLUME /data
EXPOSE 8787
ENTRYPOINT ["kinuvo"]
CMD ["serve", "--host", "0.0.0.0"]
