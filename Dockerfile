ARG DOCKER_PROXY=""
FROM ${DOCKER_PROXY}openjdk:17

COPY server/target/scala-*/*.sh.bat ./

RUN mkdir /config

CMD exec ./*.sh.bat /config/config.conf
