FROM hseeberger/scala-sbt:8u181_2.12.7_1.2.6 as builder
WORKDIR /app
ADD . /app
RUN sbt clean compile docker:stage


FROM openjdk:8
RUN apt-get update && apt-get install -y \
    tesseract-ocr
ENV TESSERACT_HOME /opt/tesseract
WORKDIR /opt/docker
COPY --from=builder /app/target/docker/stage/opt /opt
COPY --from=builder /app/tesseract /opt/tesseract/tessdata
RUN ["chown", "-R", "daemon:daemon", "."]
USER daemon
ENTRYPOINT ["/opt/docker/bin/hackfire-ocr"]
CMD []
