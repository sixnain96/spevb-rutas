# Imágenes con versión específica (reproducibles). Para actualizarlas: docs/DOCKER-Y-OPERACION.md
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
# Ejecuta las pruebas que no necesitan archivos fuera de src/ ni Docker (las demás se omiten dentro de la construcción).
RUN mvn -B verify

FROM eclipse-temurin:21.0.8_9-jre-noble
WORKDIR /app
# Usuario sin privilegios: la aplicación nunca corre como root.
RUN groupadd --system spevb && useradd --system --gid spevb --no-create-home spevb
COPY --from=build /app/target/spevb-rutas-*.jar /app/app.jar
RUN mkdir -p /app/data && chown spevb:spevb /app/data
USER spevb
EXPOSE 8080
# Sin curl en la imagen: se usa un socket TCP de bash contra /api/estado (consulta también la base de datos).
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /api/estado HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
