FROM maven:3.9.11-amazoncorretto-25-alpine@sha256:b5e9c10dadd7e7d72dcea243a68da419bfb7ec780aa50fe796fb5423c0faf1f5 AS dependency-seed

RUN apk add --no-cache git

WORKDIR /seed
COPY pom.xml ./
COPY src ./src

RUN mvn --batch-mode --no-transfer-progress -Dstyle.color=never \
      -Drat.skip=true -DskipTests dependency:go-offline && \
    mvn --batch-mode --no-transfer-progress -Dstyle.color=never \
      -Drat.skip=true -DskipTests test

FROM maven:3.9.11-amazoncorretto-25-alpine@sha256:b5e9c10dadd7e7d72dcea243a68da419bfb7ec780aa50fe796fb5423c0faf1f5

RUN apk add --no-cache git

COPY --from=dependency-seed /root/.m2/repository /opt/bat/cache/maven

RUN chmod -R a+rX /opt/bat/cache/maven

LABEL org.opencontainers.image.title="BAT offline Java worker" \
      org.opencontainers.image.description="Java 25 and an immutable Maven dependency seed for BAT's isolated pilot"
