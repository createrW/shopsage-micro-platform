# One Dockerfile for every Spring Boot service; pick the image with --build-arg SERVICE=<artifactId>.
#   build   : runs on the build host's native arch ($BUILDPLATFORM) and packages ALL modules once with
#             the repo's ./mvnw (tests are neither compiled nor run here; `./mvnw verify` runs them), then
#             extracts each jar into Spring Boot layers under /out/<service>.
#             It does not depend on SERVICE, so BuildKit shares this stage across the four images.
#   runtime : JRE-only, non-root numeric uid 1001 that can read but not modify the root-owned app files;
#             layers are copied from least to most frequently changed so a code-only change rebuilds just
#             the small `application` layer. Container arguments are passed through to the application.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-noble AS build
# The image has no unzip; without it ./mvnw silently switches to the .tar.gz distribution, whose
# hash does not match distributionSha256Sum (pinned for the .zip) and the build fails.
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY common/pom.xml common/
COPY services/user-service/pom.xml services/user-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/order-service/pom.xml services/order-service/
COPY services/payment-service/pom.xml services/payment-service/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -q dependency:go-offline
COPY common/ common/
COPY services/ services/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -q -Dmaven.test.skip=true clean package \
 && for jar in services/*/target/*.jar; do name="$(basename "$jar" .jar)"; java -Djarmode=layertools -jar "$jar" extract --destination "/out/$name"; done

FROM eclipse-temurin:17-jre-noble AS runtime
ARG SERVICE
RUN groupadd --gid 1001 app && useradd --uid 1001 --gid 1001 --no-create-home --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /out/${SERVICE}/dependencies/ ./
COPY --from=build /out/${SERVICE}/spring-boot-loader/ ./
COPY --from=build /out/${SERVICE}/snapshot-dependencies/ ./
COPY --from=build /out/${SERVICE}/application/ ./
USER 1001:1001
# Heap at 60% of the container limit: of a 1 GiB limit that leaves ~400 MiB for metaspace, code cache, thread
# stacks and Netty/Kafka direct buffers (non-heap memory the percentage does not cover).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=40 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# "--" becomes $0 of the sh -c script, so "$@" is exactly the container arguments (e.g. --server.port=9000).
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"","--"]
