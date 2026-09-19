# throttle_mini_lab

**No es un producto: es un banco de pruebas.** Existe para responder con datos, y no con opiniones, a una pregunta concreta:

> ¿Me conviene Redis para limitar las peticiones de mi aplicación, o me basta con un contador en memoria o con Postgres?

El laboratorio es consecuencia directa de un problema real con el que me encontré en mi proyecto anterior y aplica **el mismo límite** con **tres implementaciones distintas**, las somete al **mismo perfil de carga** y emite un **veredicto con la evidencia generada**. La mitad de su valor es la otra mitad de la frase: **decirte cuándo NO usar Redis**.

## Qué mide

- Latencia **p50 / p95 / p99** (nunca promedios) y throughput, por implementación.
- **Exactitud**: cuántas peticiones deja pasar cada algoritmo frente al límite teórico.
- Coste de recursos: memoria de Redis, WAL/bloat y contención en Postgres, CPU.

## Las tres implementaciones

| Adaptador              | Cómo limita                                                                | Qué pierde                                                   |
| ---------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------ |
| `InProcessRateLimiter` | contadores en el heap (`ConcurrentHashMap` + `LongAdder`)                  | no sirve con varias instancias; se pierde al reiniciar       |
| `PostgresRateLimiter`  | cubos con `INSERT … ON CONFLICT … RETURNING` (una sentencia atómica)       | escrituras, WAL, bloat, contención por fila                  |
| `RedisRateLimiter`     | `INCR`+`EXPIRE`, ZSET (sliding log), sliding counter y token bucket en Lua | un servicio más, RAM, durabilidad no garantizada por defecto |

Las tres viven detrás de una única interfaz `RateLimiter`. Aquí sí se justifica: el producto **es** compararlas.

## Stack

- **Backend**: Java 21 + Spring Boot 4.1
- **Frontend**: Next.js (App Router, TypeScript, Tailwind)
- **Datos**: PostgreSQL + Redis (Docker Compose)

## Estructura

```
apps/
  backend/    Spring Boot — API (perfil `api`) + generador de carga (perfil `runner`)
  web/        Next.js — gestión de claves y comparador
docs/
  PLAN.md     plan de desarrollo por fases
  decisions/  ADRs
```

## Dev

Requisitos: JDK 21, Node.js 24+, Docker.

```bash
# Infraestructura (Postgres + Redis)
docker compose up -d

# API (desde apps/backend) → http://localhost:8000
./mvnw spring-boot:run

# Generador de carga (desde apps/backend) → mismo JAR, otro perfil, sin servidor web
./mvnw spring-boot:run -Dspring-boot.run.profiles=runner

# Web (desde apps/web) → http://localhost:3000
npm install
npm run dev
```

Comprobación: `curl http://localhost:8000/healthz`
