# syntax=docker/dockerfile:1
# ── Compilación ───────────────────────────────────────────────────────
# Mismo esquema que stack-line-services: dependencias en una capa aparte del
# código, para que cambiar una clase no vuelva a bajar todo Maven Central.
#
# En deploy/ y no en la raíz: el plugin de Micronaut, si encuentra un
# Dockerfile en la raíz, cambia su tarea `dockerfile` y rompe el build.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# gradle.properties pide 4 GB para Gradle, pensado para la máquina de
# desarrollo. En el servidor (2–4 GB en total, con el ERP corriendo al lado)
# eso tumba la compilación: se baja a 1 GB y Kotlin compila dentro del mismo
# proceso en vez de levantar un daemon aparte.
ENV GRADLE_FLAGS="--no-daemon -Dorg.gradle.jvmargs=-Xmx1g -Pkotlin.compiler.execution.strategy=in-process"

# La caché de Gradle (~/.gradle) va en un montaje de BuildKit que sobrevive
# entre despliegues: si Docker descarta la capa de dependencias (prune, cambio
# en build.gradle.kts) no se vuelve a bajar todo. El reporte de `dependencies`
# va a /dev/null: es el árbol completo y solo llenaba el log del despliegue.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,id=gradle-agents,target=/root/.gradle \
    chmod +x gradlew && ./gradlew $GRADLE_FLAGS dependencies > /dev/null || true

COPY src src
RUN --mount=type=cache,id=gradle-agents,target=/root/.gradle \
    ./gradlew $GRADLE_FLAGS shadowJar --quiet

# ── Ejecución ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre
WORKDIR /app

# Usuario sin privilegios, igual que el backend.
RUN useradd --system --uid 10002 agents
USER agents

COPY --from=build --chown=agents /src/build/libs/*-all.jar app.jar

# Solo lo alcanzan el backend y Caddy por la red interna de Docker; no se
# publica a internet.
EXPOSE 8081
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
