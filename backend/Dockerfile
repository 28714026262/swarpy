FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml dependency:go-offline -q
COPY backend/src backend/src
COPY prototype prototype
RUN mvn -f backend/pom.xml package -DskipTests -q

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-writer fonts-noto-cjk git && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 swapy && mkdir -p /app/data && chown -R swapy:swapy /app
WORKDIR /app
COPY --from=build /source/backend/target/swapy-0.1.0.jar /app/swapy.jar
USER swapy
EXPOSE 4175
ENTRYPOINT ["java","-Xmx1536m","-jar","/app/swapy.jar","--server.address=0.0.0.0"]
