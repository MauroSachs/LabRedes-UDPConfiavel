# Usa a imagem base do OpenJDK 11 com o Maven instalado
FROM maven:3.9.4-amazoncorretto-21 AS build

# Define o diretório de trabalho dentro do contêiner
WORKDIR /app

# Copia o arquivo pom.xml para o diretório de trabalho
COPY pom.xml .

# Baixa as dependências do Maven (isto é separado para aproveitar o cache de dependências)
RUN mvn dependency:go-offline

# Copia todo o código-fonte para o diretório de trabalho
COPY src ./src

# Compila o projeto usando Maven
RUN mvn package -DskipTests

# Segunda etapa para criar a imagem final do Docker
FROM docker.io/amazoncorretto:21-alpine-jdk

RUN apk update && apk add --no-cache tcpdump iproute2

# Define o diretório de trabalho como /app
WORKDIR /app

# Copia o arquivo JAR construído na primeira etapa
COPY --from=build /app/target/*.jar app.jar

EXPOSE 9876/udp

CMD ["java", "-jar", "app.jar"]
