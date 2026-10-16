# A demo node. Built from the jar that ./mvnw package produced on the host:
# the ExtremeTransactions artifacts are not available inside a Docker build.
FROM eclipse-temurin:17-jdk AS extract
WORKDIR /extract
COPY target/spring-boot-sample-jta-atomikos-logcloud-*.jar app.jar
RUN jar xf app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
# exploded, so that the entrypoint can leave the LogCloud jar off the classpath
COPY --from=extract /extract/BOOT-INF/lib lib
COPY --from=extract /extract/BOOT-INF/classes classes
COPY docker/entrypoint.sh entrypoint.sh
RUN chmod +x entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]
