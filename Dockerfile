# Spring Cloud Gateway BFF image for Railway.
#
# This is the ONLY publicly routed service. The browser talks to it and nothing
# else; it holds the OAuth2 session and relays access tokens to the backend.

FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

COPY src ./src
RUN ./gradlew clean build -x test --no-daemon
RUN cp "$(ls -1 build/libs/*.jar | grep -v plain)" app.jar

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=build /app/app.jar /app/app.jar

EXPOSE 8090

# Railway's private network is IPv6-only, but *.railway.internal publishes only
# AAAA records, so the JVM reaches it without any address-preference flag.
# Do NOT add -Djava.net.preferIPv6Addresses=true: it makes IPv4-only upstreams
# (a public Keycloak, for instance) fail with "Network unreachable".
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
