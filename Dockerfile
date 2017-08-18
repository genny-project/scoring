FROM openjdk:8u131-jre-alpine
RUN apk update && apk add jq
 
ADD target/scoring-0.0.1-SNAPSHOT-fat.jar /service.jar
ADD cluster.xml /cluster.xml

ADD realm /realm
ADD docker-entrypoint.sh /docker-entrypoint.sh

WORKDIR /

EXPOSE 5701
EXPOSE 8085

#CMD ["java"]
ENTRYPOINT [ "/docker-entrypoint.sh" ]

