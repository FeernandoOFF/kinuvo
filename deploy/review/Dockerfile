FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data kinuvo \
    && mkdir /data && chown kinuvo /data
COPY dist/kinuvo.jar /opt/kinuvo/kinuvo.jar
COPY deploy/review/supervisor.py /opt/kinuvo/supervisor.py
USER kinuvo
ENV KINUVO_HOME=/data/state KINUVO_REVIEW_MODE=true KINUVO_BEHIND_PROXY=1
EXPOSE 8787
ENTRYPOINT ["python3", "-u", "/opt/kinuvo/supervisor.py"]
