FROM openjdk:11.0-jdk
WORKDIR /app

COPY gradle /app/gradle
#COPY .gradle /app/.gradle
COPY build.gradle settings.gradle start.sh gradlew /app/
RUN ./gradlew tasks

COPY src /app/src

#RUN ./gradlew build -i

ENTRYPOINT ["./start.sh"]

#RUN ./gradlew shadowJar


#FROM openjdk:11.0-jre
#WORKDIR /app
#RUN mkdir out
#COPY --from=0 /app/build/libs/Dcn.jar . 
#COPY config.txt .
#ENTRYPOINT ["java", "-jar", "Dcn.jar"]

