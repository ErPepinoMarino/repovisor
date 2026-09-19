# throttle_mini_lab — Plan de desarrollo

> **Qué es.** Un banco de pruebas local para decidir **cómo** implementar el límite de peticiones de una aplicación: **contador en memoria del proceso · Postgres · Redis**. Aplica el mismo límite de tres formas distintas, las somete a **el mismo perfil de carga** y publica un **veredicto con la evidencia generada**.
>
> **Incluye deliberadamente los casos en los que Redis no vale la pena.** La mitad del valor de esta herramienta es decirte **cuándo NO usarlo**. Si una corrida demuestra que un `ConcurrentHashMap` gana, se publica igual.
>
> **Objetivo personal.** Aprender de verdad **Java moderno, Spring Boot y Redis** construyendo algo terminado, medible y reproducible. El proyecto es la excusa; el aprendizaje es el producto.
>
> **No es un producto.** Nadie vende nada aquí: no hay clientes, ni precios, ni planes comerciales. Alguien se lo monta en su casa o en un servidor propio y lo usa para decidir.
>
> **Sin deployment público.** Corre en local con Docker Compose. Se demuestra con un informe reproducible y una demo grabada en vídeo.

---

## Cómo usar este plan

- Trabajamos **fase por fase, en orden**. Nunca se salta una fase.
- Antes de implementar cada fase: 1) entender el objetivo, 2) revisar las tecnologías nuevas, 3) definir exactamente qué se construye, 4) implementar, 5) probar, 6) verificar los criterios de aceptación.
- Cada fase termina con **el sistema funcionando de punta a punta**; nunca con una pieza a medias ni con "código desechable" que se tira en la fase siguiente.
- La validación se hace siempre con **`mvn clean verify`**, nunca con `mvn compile`. `mvn compile` no recompila si las clases son más nuevas que las fuentes y devuelve falsos verdes: en este proyecto ya ocultó una clase inexistente que rompía el build.
- Cuando una fase propone alternativas, hay una **recomendación** con su justificación.

## Principios rectores (no negociables)

1. **Medir, no opinar.** Ninguna afirmación del README existe sin una corrida reproducible que la respalde y cite sus propias cifras.
2. **Decir cuándo NO usar Redis es parte del producto.** Una herramienta que siempre vende Redis no sirve: es publicidad.
3. **La UI visualiza; el servidor mide.** Ningún tiempo se toma en el navegador: el event loop de JavaScript y la red invalidan la medición.
4. **Comparación justa o no es comparación.** Mismos datos, misma carga, mismo entorno, y **modos de medición declarados** (nunca 1 conexión síncrona contra un pool de 10 sin decirlo).
5. **Percentiles, nunca promedios.** p50/p95/p99 más dispersión. El promedio esconde exactamente lo que interesa (la cola y los picos).
6. **El generador de carga no compite con lo medido.** Vive en otro proceso y sin servidor web. Si comparte JVM con la API, la medición es basura.

## A. Arquitectura final

```text
┌────────────────────────────────┐
│  Next.js (web)                 │  ① describe el perfil de carga
│  React + Tailwind              │  ② lanza una corrida y la ve en vivo
│                                │  ③ lee el veredicto y el histórico
└───────────┬────────────────────┘
            │ HTTP / REST
┌───────────▼────────────────────┐        ┌────────────────────────────┐
│  Spring Boot · perfil `api`    │───────►│  PostgreSQL                │
│  · endpoints de ejemplo        │        │  · claves y cuotas         │
│  · filtro de límite (3 impl.)  │        │  · cubos del limitador     │
│  · API del comparador          │        │  · corridas y resultados   │
└───────────┬────────────────────┘        └────────────────────────────┘
            │
            │  implementaciones intercambiables
┌───────────▼─────────────────────┐       ┌────────────────────────────┐
│  RateLimiter  (interfaz)        │       │  Redis                     │
│  ├── InProcessRateLimiter       │       │  · ventanas, tokens, ZSET  │
│  ├── PostgresRateLimiter  ──────┼──────►│  · TTL + scripts Lua       │
│  └── RedisRateLimiter     ──────┘       │  · efímero y prescindible  │
└─────────────────────────────────┘       └────────────────────────────┘

┌────────────────────────────────┐
│  Spring Boot · perfil `runner` │  genera la carga y reporta al API.
│  sin servidor web              │  Proceso aparte a propósito.
└────────────────────────────────┘
```

| Componente | Proceso | Responsabilidad |
|---|---|---|
| `web` | Next.js | Producto: describir la carga, lanzar la corrida, ver el veredicto. **No mide.** |
| `api` | Spring Boot (`--spring.profiles.active=api`) | Endpoints de ejemplo + filtro de límite + API del comparador. Aplica el límite; no lo mide. |
| `runner` | Spring Boot (`--spring.profiles.active=runner`) | Genera la carga, mide, agrega y reporta. **No expone HTTP.** |
| `postgres` | PostgreSQL | Fuente de verdad: claves, cuotas, cubos del limitador y **resultados de las corridas**. |
| `redis` | Redis | Estado **efímero** del limitador. Si se vacía, el sistema sigue funcionando. |

### Decisiones que cierran la arquitectura

1. **Un solo proyecto Spring Boot, dos perfiles (`api` y `runner`).** Comparten modelos y configuración; separarlos en dos módulos solo añade plomería. El `runner` arranca con `spring.main.web-application-type=none`: **un generador de carga que además sirve páginas no es un generador de carga**, y compartir JVM con la API contaminaría la latencia medida (GC, hilos, planificador).
2. **Una interfaz, tres adaptadores.** `RateLimiter` con implementaciones en memoria, Postgres y Redis. Es el único sitio del stack donde una abstracción con varias implementaciones se justifica **hoy**: el producto *es* compararlas. No es especulación sobre "algún día quizá otra base de datos".
3. **Las tres implementaciones del límite se prueban contra el MISMO contrato de tests.** Un mismo test de exactitud y un mismo test de concurrencia corren contra las tres; si un adaptador no los pasa, no es una implementación válida (es una fuente de conclusiones falsas).
4. **El perfil de carga es un dato persistido, no unos parámetros de línea de comandos.** Se guarda en Postgres junto con el commit y la configuración del entorno, de modo que cualquier corrida se puede **repetir** y cualquier conclusión se puede **rebatir**.
5. **Redis es efímero y prescindible.** Nunca guarda resultados ni estado de negocio: solo las ventanas del limitador, que caducan solas. Un `FLUSHALL` no pierde nada que importe. Los resultados de las corridas viven en Postgres.
6. **La UI nunca mide.** Solo pide, visualiza y compara lo que el servidor ya midió. Es la regla que impide el error clásico de medir en el navegador.
7. **El entorno se declara en el resultado.** CPU, memoria, si el cliente corrió dentro o fuera de la red de Docker y la versión de cada servidor. Un benchmark sin entorno no es un dato, es un rumor.

## B. Conceptos que hay que dominar antes de medir

Sin este vocabulario, los números que produzca el laboratorio no significarán nada.

### B.1 Vocabulario de medición

| Término | Qué es | Por qué importa aquí |
|---|---|---|
| **Latencia** | tiempo de **una** petición | lo que sufre quien usa el sistema |
| **Throughput** | peticiones por segundo | lo que aguanta el sistema en total |
| **p50 / p95 / p99** | percentiles de latencia | el p99 es lo que ve el usuario cuando el sistema va cargado; el promedio lo esconde |
| **Dispersión** | variabilidad entre corridas | un número sin dispersión no es un dato |
| **Warmup** | calentar antes de medir | JIT de la JVM, pool de conexiones, caché de Postgres. Sin calentar, mides el arranque |
| **RTT** | ida y vuelta por red | en local puede dominar más que el propio cálculo |
| **Pipelining** | agrupar comandos en un solo envío | multiplica el throughput de Redis sin tocar el servidor |
| **Hot key / distribución zipf** | unas pocas claves recibiendo casi toda la carga | es la carga **real**; la distribución uniforme es una ficción que favorece a quien mide mal |
| **Exactitud** | decisiones acertadas frente al límite teórico | un limitador rápido e incorrecto es peor que uno lento y correcto |

### B.2 Los límites reales de Redis (lo que este laboratorio exhibe)

1. **Single-threaded**: un único hilo ejecuta los comandos. Ese es su techo real: **un core**. Escala con pipelining y con más instancias, no con más hilos.
2. **El round-trip manda**: con una conexión síncrona se paga un RTT por operación. Con pipelining, o con Lua (varios comandos en un solo viaje), el throughput cambia de orden de magnitud. Comparar sin declararlo es hacer trampa.
3. **Todo vive en RAM**: el dataset completo compite con tu presupuesto de memoria. El coste por clave y la fragmentación se ven con `MEMORY USAGE` y `INFO memory`.
4. **Picos por persistencia**: `BGSAVE` y la reescritura del AOF hacen `fork()` con copy-on-write y provocan **picos de latencia** visibles en el p99. Es comportamiento real, no un fallo.
5. **Una sola clave caliente = un solo core**: las *hot keys* no se reparten solas; hay que diseñar el reparto (sharding de clave) y aquí se puede medir cuánto se gana.
6. **Eviction silenciosa**: con `maxmemory` y política LRU/LFU, Redis borra claves sin avisar. Un limitador con eviction agresiva **deja de limitar**, y eso aparece en el eje de exactitud, no en el de latencia.

### B.3 Los límites reales de Postgres (para no caricaturizarlo)

1. **Una fila caliente = contención**: muchas escrituras concurrentes sobre la misma fila se serializan por bloqueo y el throughput se derrumba.
2. **Cada escritura es WAL y `fsync`**: eso es **durabilidad**, no un defecto. Pagar latencia por durabilidad es un *trade-off*, no una derrota.
3. **Bloat y autovacuum**: las escrituras repetidas generan versiones muertas de la fila; la tabla se hincha y hay que limpiarla.
4. **Si el working set cabe en `shared_buffers`, Postgres puede empatar o ganar** contra Redis. Esa es la conclusión más valiosa que puede producir el laboratorio: **cuándo Redis no aporta nada**.
5. **El pool de conexiones es su techo práctico**: más hilos que conexiones disponibles no mejoran nada.
6. **Y donde Redis no puede competir**: `JOIN`, agregaciones ad-hoc, transacciones entre varias entidades y cualquier pregunta que no conozcas de antemano.



## C. El producto

### C.1 Las tres pantallas

1. **Claves y cuotas** — crear y revocar claves de prueba y asignarles un escenario de cuota. Aquí se ve el límite funcionando de verdad (también con `curl`).
2. **Comparador** — el corazón. Describir el perfil de carga, lanzar la corrida, y verla en vivo: latencia p50/p95/p99, throughput, errores, **exactitud** y el estado interno de los motores bajo carga.
3. **Informe y veredicto** — histórico de corridas y la conclusión argumentada de una corrida concreta, reproducible y descargable.

### C.2 El perfil de carga (lo que describe quien usa la herramienta)

| Parámetro | Por qué importa |
|---|---|
| **rps objetivo** | es el eje de la decisión: todo cambia con el volumen |
| **nº de claves activas** | distingue un patrón ancho de uno estrecho |
| **distribución** (uniforme / zipf) | clave caliente frente a carga repartida: el caso real |
| **forma de la carga** (sostenida / ráfaga) | un límite de rps y un límite de *burst* son cosas distintas |
| **ratio lectura/escritura** | cambia el ganador por completo |
| **duración y warmup** | sin estabilización, lo que se mide es el arranque |
| **ubicación del cliente** (dentro o fuera de la red de Docker) | puede dominar la latencia medida |
| **implementaciones a comparar** | una, dos o las tres |

### C.3 Lo que devuelve: el veredicto

No es un gráfico: es una **frase argumentada con las cifras de esa corrida**. Por ejemplo:

> *"Con 400 rps, 200 claves y distribución zipf, el contador en memoria sostiene 0,3 ms de p99 y **no necesitas nada más** si tu aplicación corre en un solo proceso. Postgres aparece con 2,1 ms de p99 y contención creciente al superar N escrituras/s sobre la misma clave. Redis baja a 0,6 ms, pero añade un servicio, ~Y MB de RAM y no te da durabilidad con su configuración por defecto. **Para tu caso, Redis no se paga** salvo que necesites que varias instancias compartan el límite."*

El veredicto se compone de tres bloques, siempre separados:
1. **Ganador por eje** (latencia, throughput, exactitud, coste de recursos).
2. **Evidencia**: las cifras concretas de la corrida que lo respaldan.
3. **Criterios no medibles**: lo que los números no pueden decidir por ti.

### C.4 Los criterios no medibles (también son parte del veredicto)

Ningún benchmark decide esto, y fingir lo contrario sería deshonesto:

- **¿Necesitas que varias instancias compartan el límite?** Es la pregunta que casi decide sola la respuesta: si la respuesta es sí, un contador en memoria queda descartado y Redis es la opción natural.
- **¿Ya tienes Redis en el stack?** El coste marginal de un servicio que ya existe es prácticamente cero; añadirlo de nuevo no lo es.
- **¿Puedes tolerar perder las ventanas al reiniciar?** Si no, un contador en memoria está fuera.
- **¿Necesitas auditar el uso histórico?** Entonces la respuesta es Postgres, aunque sea más lento.
- **¿Quién mantiene el servicio y quién lo conoce?** Un componente más en producción tiene un coste que no sale en ningún gráfico.



## D. Rigor metodológico (las reglas del banco de pruebas)

Estas reglas no son un apéndice: son lo que separa una herramienta de un tutorial. Cada una nace de un error concreto que invalidaría las conclusiones.

1. **Warmup obligatorio y declarado.** Se descartan las primeras N peticiones (o M segundos) antes de medir: JIT de la JVM, pool de conexiones, caché en frío de Postgres. El warmup aplicado se guarda en el resultado.
2. **Repeticiones y dispersión.** Cada punto se mide al menos 3 veces y se publica la mediana con su dispersión. **Nunca "el mejor de 5"**: eso es marketing, no medición.
3. **Percentiles, nunca promedios.** p50, p95, p99 (y p99.9 si la duración lo permite).
4. **Mismo dataset y mismo orden de claves** para las tres implementaciones: se genera una vez y se reutiliza.
5. **Distribución zipf además de uniforme.** La carga uniforme esconde las claves calientes y favorece las conclusiones fáciles.
6. **El modo de medición forma parte del resultado.** Comparar M1 con M2 es inválido, así que el modo entra en la clave de la corrida y el sistema no permite mezclarlos.
7. **El generador de carga no compite con lo medido** (proceso aparte, sin servidor web) y **la latencia se toma en el cliente instrumentado**, nunca en el navegador ni deducida de logs.
8. **El entorno va dentro del resultado**: CPU, núcleos, RAM, versión de Postgres, versión de Redis, `maxmemory` y su política, commit del código y si el cliente corrió dentro o fuera de la red de Docker.
9. **Nada se descarta porque contradiga la hipótesis.** Si Redis pierde en un escenario, el escenario se publica con su explicación.
10. **Una conclusión sin su corrida no se publica.** El README cita el identificador de la corrida que respalda cada afirmación.

### E. Los tres modos de medición

| Modo | Cómo | Qué mide | Qué NO permite concluir |
|---|---|---|---|
| **M1 · Round-trip puro** | 1 conexión, una operación a la vez, sin paralelismo | latencia limpia: red + servidor + protocolo | nada sobre throughput ni escalabilidad |
| **M2 · Saturación** | pool de conexiones + pipelining/Lua, N hilos | el techo práctico de cada motor | no dice cómo se comporta a la carga real |
| **M3 · Escenario real** | el perfil que describe quien usa la herramienta (zipf, ratio, ráfaga) | la decisión: cuál conviene *en este caso* | no es un techo: es un punto del espacio |

Los tres son necesarios: **M1** compara sin trampa, **M2** encuentra el límite, **M3** responde la pregunta. Publicar solo uno sería, respectivamente, ingenuo, engañoso o anecdótico.



## F. El dominio

### F.1 Escenarios de cuota (no "planes comerciales")

Un **escenario de cuota** es un conjunto de límites con nombre, para tener algo realista que aplicar. No es una oferta ni un precio:

| Campo | Ejemplo |
|---|---|
| `name` | `holgado`, `ajustado`, `rafaga_corta` |
| `requestsPerSecond` | 10 / 100 / 1000 |
| `burst` | ráfaga permitida por encima del rps sostenido |
| `dailyQuota` | cuota diaria (contador aparte, de vida larga) |
| `weightPerEndpoint` | no todos los endpoints cuestan lo mismo |

El **peso por endpoint** es lo que hace el laboratorio realista: limitar 100 peticiones baratas no es lo mismo que 100 que consultan la base de datos. Sin eso, el trabajo del limitador no se parece al de producción.

### F.2 Claves de prueba

- `id`, `keyHash` (**nunca se guarda la clave en claro**), `quotaScenario`, `status` (`ACTIVE`/`REVOKED`), `createdAt`.
- Se generan desde la UI o por `curl`; son para someterlas a carga.

### F.3 Endpoints

| Endpoint | Coste | Existe para |
|---|---|---|
| `GET /healthz` | nulo | comprobar que está vivo |
| `GET /v1/fast` | bajo | responder ya: aísla el coste del **limitador** del coste del trabajo |
| `GET /v1/db` | medio | leer de Postgres: compite por el mismo recurso que `PostgresRateLimiter` |
| `GET /v1/compute` | alto | cálculo deliberadamente caro: el límite se amortiza |
| `GET /v1/quota` | — | consultar el estado de la propia cuota |

### F.4 Respuestas y cabeceras

Petición permitida → respuesta normal + cabeceras estándar:

```
RateLimit-Limit: 100
RateLimit-Remaining: 42
RateLimit-Reset: 7
```

Petición rechazada → `429 Too Many Requests` con `Retry-After` y el mismo juego de cabeceras.

Detalle de calidad: los tres valores se calculan **en la misma operación** que aplica la decisión (un único script Lua en Redis, un único `RETURNING` en Postgres, una única sección crítica en memoria). Si se calculan después, hay una carrera entre el valor que se informa y el que se aplicó.

### F.5 Política ante caída del backend: `fail-open` o `fail-closed`

Si Redis o Postgres no responden, el limitador no puede decidir. Hay dos respuestas legítimas y **es una decisión de negocio, no técnica**:

| Política | Comportamiento | Cuándo tiene sentido |
|---|---|---|
| `fail-open` | deja pasar las peticiones | el límite protege capacidad, no seguridad: es preferible servir de más que caer entero |
| `fail-closed` | rechaza todo | el límite cobra dinero o protege un recurso escaso: es preferible rechazar que regalar |

Se configura por escenario de cuota y **se prueba con un test** que corta el backend a propósito. Es exactamente el tipo de detalle que separa un limitador de juguete de uno real.

### F.6 Los cinco algoritmos del límite

El "abanico de posibilidades" no son comandos sueltos: son cinco formas de resolver el mismo problema, cada una con su eje de *trade-off*.

| Algoritmo | Idea | Exactitud | Coste |
|---|---|---|---|
| **Fixed window** | contador por intervalo de tiempo (`INCR` + `EXPIRE`) | ⚠️ permite el doble del límite en el borde de la ventana | mínimo: 1 clave por período |
| **Sliding window log** | registro con marca de tiempo de cada petición (ZSET) | ✅ exacto | memoria O(n) por clave: crece con el tráfico |
| **Sliding window counter** | pondera la ventana anterior y la actual | 🟡 aproximado (~1-3 % de error) | O(1): dos contadores |
| **Token bucket** | cubo de fichas que se rellena a ritmo constante | ✅ exacto y **admite ráfagas** | pequeño y constante; es lo que usan Stripe, Cloudflare y Kong |
| **Leaky bucket** | cola que se vacía a ritmo constante | ✅ exacto, sin ráfagas | pequeño; suaviza en vez de permitir picos |

Medir el mismo escenario con los cinco **es** el contenido didáctico: el eje exactitud↔coste se ve con los propios ojos, y el conocido "problema del borde" de la ventana fija se demuestra con un test, no con una afirmación.



## G. Las tres implementaciones detrás de una interfaz

```java
public interface RateLimiter {
    Decision decide(Request request);
}

public record Request(String key, String endpoint, int cost) {}

public record Decision(boolean allowed, long limit, long remaining,
                       Duration resetIn, String reason) {}
```

La interfaz es **estrecha y sin fugas**: no expone `RedisTemplate`, ni `EntityManager`, ni `Connection`. Si algo de eso aparece en la firma, la abstracción ya se ha roto y las mediciones dejan de ser comparables entre sí.

| | `InProcessRateLimiter` | `PostgresRateLimiter` | `RedisRateLimiter` |
|---|---|---|---|
| **Estructura** | `ConcurrentHashMap` + `LongAdder`, ventanas con reloj monótono | tabla de cubos, una sentencia `INSERT … ON CONFLICT … RETURNING` | claves con TTL, contadores, ZSET y **scripts Lua** |
| **Atómico** | sección crítica con `compute()` o lock | sí: una sola sentencia | sí: Lua se ejecuta sin interrupción |
| **Multi-instancia** | ❌ no | ✅ sí | ✅ sí |
| **Sobrevive un reinicio** | ❌ no | ✅ sí |  depende de cómo se configure la persistencia |
| **Enseña en Java** | concurrencia en el JVM, coste real del lock, `LongAdder` frente a `AtomicLong` | JDBC/JPA, `ON CONFLICT`, contención, transacciones cortas | `StringRedisTemplate`, `DefaultRedisScript`, pool de conexiones |
| **Enseña en la práctica** | por qué "no necesito nada más" es tantas veces la respuesta correcta | por qué la durabilidad se paga en latencia | por qué Redis es *la* respuesta a "varios procesos comparten el límite" |
| **Su límite** | no comparte estado entre procesos | fila caliente → contención; bloat y vacuum | un solo hilo, RAM, no durable por defecto |

**Las tres se validan contra el mismo contrato de tests.** Un mismo test de exactitud y un mismo test de concurrencia corren contra las tres implementaciones (misma clase abstracta, tres subclases con su configuración). Si un adaptador no los pasa, no es una implementación válida: es una fuente de conclusiones falsas.

## H. Roadmap por fases

```text
P0   Fundación y renombrado: monorepo, perfiles, ADRs, compose            ✔ hecho
P1   Dominio + API de claves y escenarios de cuota (REST + JPA + Flyway)
P2   RateLimiter en memoria + filtro + cabeceras RateLimit + 429
P3   RateLimiter en Postgres (cubos atómicos) + fail-open/fail-closed
P4   Redis entra en juego: INCR/EXPIRE → ZSET → conteo ponderado → token bucket en Lua
P5   El instrumento: runner en proceso aparte, warmup, percentiles, modos M1/M2/M3
P6   La corrida como dato: persistir perfil, resultado y entorno; repetibilidad
P7   El veredicto: motor de conclusiones sobre la corrida + informe descargable
P8   Frontend: claves, comparador en vivo por SSE, informe
P9   Redis a fondo: SLOWLOG, MEMORY USAGE, eviction, picos por BGSAVE, hot keys
P10  Docker (5 servicios), smoke test, CI, demo grabada y README de portfolio
```

**Orden elegido y por qué:**

- **Los tres limitadores antes que el instrumento de medida (P2→P4, luego P5).** Construir primero un medidor provisional y reescribirlo después es el camino a un benchmark de juguete. El instrumento se construye **una vez**, cuando ya existen las tres cosas que debe medir.
- **El frontend al final (P8).** Ya se domina Next/React; el aprendizaje está en Java, Spring y Redis. Poner el frontend al final evita reescribir UI cada vez que cambie el contrato, y el contrato se congela antes porque los tests de P1-P7 **son** el contrato.
- **Redis entra cuando hay algo que comparar (P4)**, no antes: así cada primitiva de Redis (contador con TTL, ZSET, Lua) resuelve un problema que ya existe, en lugar de ser una pieza colocada por adelantado.
- **`fail-open`/`fail-closed` en P3**, cuando ya hay un backend que se puede caer a propósito y un test que lo demuestra.

**Gate de cada fase:** no se pasa a la siguiente hasta que `mvn clean verify` esté verde con los tests de esa fase escritos. Ninguna fase se cierra con tests pendientes.

## I. Detalle de las fases

Cada fase incluye: Objetivo · Motivación · Nuevos conocimientos · Tecnologías · Implementación · Resultado verificable · Criterios de aceptación · Riesgos · Qué NO hacer todavía.

---

### FASE P0 — Fundación y renombrado ✔ completada

**Objetivo**
Monorepo limpio bajo el nombre definitivo, con el proyecto Spring Boot arrancando en dos perfiles (`api` y `runner`), el compose de Postgres y Redis, y las decisiones iniciales escritas como ADR.

**Qué se hizo**
- Eliminado el dominio anterior (`com.repovisor.mock`) y todo el código muerto del proyecto descartado. **Se eliminó también el build roto**: la clase `AnalysisNotFoundException` no existía y `mvn compile` daba un falso verde porque no recompilaba clases sin cambios.
- Renombrado a `throttle-mini-lab`: `artifactId`, `name`, paquete base `com.throttlelab`, clase principal `ThrottleLabApplication`, nombres de contenedor y proyecto de compose, base de datos y usuario de Postgres, README y remote de git.
- `application.yml` con perfil por defecto `api`; `application-api.yml` fija el puerto 8000; `application-runner.yml` arranca **sin servidor web** (`web-application-type: none`).
- `HealthController` extraído a `com.throttlelab.api` y devolviendo un `Map` (antes era una cadena JSON escrita a mano, dentro de la clase principal).

**Resultado verificable**
- `mvn clean verify` verde; `contextLoads` pasa.
- `./mvnw spring-boot:run` → `GET http://localhost:8000/healthz` responde `{"status":"ok"}`.
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=runner` arranca y **no abre ningún puerto**.
- `docker compose config` válido; `docker compose up -d` levanta `throttle-mini-lab-postgres` y `throttle-mini-lab-redis` con healthchecks verdes.

**Qué NO hacer todavía**
- JPA, Flyway, limitadores, comparador, frontend, Actuator, SSE.

---

### FASE P1 — Dominio y API de claves y cuotas

**Objetivo**
La API con persistencia real: entidades `ApiKey` y `QuotaScenario`, migraciones Flyway, endpoints REST de creación/consulta/revocación, validación y contrato de errores. El sistema todavía **no limita nada**.

**Motivación**
Sin dominio no hay nada que limitar. Esta fase es donde se aprende Spring Boot en serio (DI, JPA, Flyway, validación, errores) **sin la complejidad de la concurrencia y de Redis encima**. Se hace primero porque es lo más cercano a lo ya conocido y lo que permite que las fases siguientes tengan sobre qué trabajar.

**Nuevos conocimientos**
- **Java moderno**: `record` para DTOs, `Optional`, streams, `List.of()`, `sealed` para resultados, `var` con criterio.
- **Spring Boot en serio**: `@Service`, `@Repository`, `@RestController`, inyección por constructor (nunca `@Autowired` en campos), `@ConfigurationProperties`, `@Validated`.
- **Spring Data JPA**: `JpaRepository`, `@Entity`, `@Id`, `@GeneratedValue`, `@Enumerated`, `@Column(columnDefinition = "jsonb")`, `@JdbcTypeCode(SqlTypes.JSON)`, `Optional<T>` en los `findBy`.
- **Flyway**: `V1__…sql`, migraciones reproducibles, `ddl-auto: validate` (el esquema lo define Flyway, no Hibernate).
- **Validación**: `spring-boot-starter-validation`, `@Valid`, `@NotBlank`, `@Min`, y un `@RestControllerAdvice` con un **cuerpo de error estable** (mismo formato para 400, 404 y 409).
- **UUID nativo de Postgres** y columnas **JSONB** desde Java.
- **Jackson** en el borde HTTP: `@JsonProperty`, `@JsonInclude`, `Instant` en ISO-8601, y por qué no se serializan entidades JPA directamente.

**Tecnologías**
`spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-flyway`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `spring-boot-starter-data-jpa-test`.

**Implementación**
- Entidades `ApiKey` (`id`, `keyHash`, `scenario`, `status`, `createdAt`, `revokedAt`) y `QuotaScenario` (`name`, `requestsPerSecond`, `burst`, `dailyQuota`, `weights` JSONB, `failurePolicy`).
- **La clave no se guarda en claro**: se persiste un hash y la clave en claro se muestra **una sola vez** en la respuesta de creación. Es el comportamiento real y evita un error clásico.
- `ApiKeyRepository`, `QuotaScenarioRepository`, servicios y mapper *función pura* entidad→DTO (sin MapStruct todavía).
- Endpoints: `POST /v1/keys` (devuelve la clave en claro una vez), `GET /v1/keys`, `GET /v1/keys/{id}`, `DELETE /v1/keys/{id}` (revocar), `POST/GET /v1/scenarios`.
- Migración `V1__create_api_key_and_quota_scenario.sql` con **datos iniciales** de escenarios (`holgado`, `ajustado`, `rafaga_corta`) para no arrancar en vacío.
- `spring.datasource` y Flyway en `application.yml` con variables de entorno y valores por defecto locales.
- CORS para el futuro frontend en `:3000`.

**Resultado verificable**
- `curl -X POST localhost:8000/v1/keys` devuelve 201 con la clave y su escenario; `GET /v1/keys` la lista **sin** la clave.
- URI inválida → 400 con el mismo formato de error que el resto.
- Id inexistente → 404 con el mismo formato.
- Desde esquema vacío (`DROP SCHEMA public CASCADE`), reiniciar la API recrea todo y siembra los escenarios.

**Criterios de aceptación**
- `ddl-auto: validate` en verde: si una entidad y el SQL se desincronizan, el arranque falla (no se permite divergencia silenciosa).
- La clave no aparece nunca en claro salvo en la respuesta de creación (comprobado con un test).
- `mvn clean verify` verde con los tests de la fase.

**Riesgos**
- Trampas de JPA (lazy loading, transacciones fuera de contexto) → `@Transactional(readOnly = true)` en lecturas, sin relaciones perezosas en el MVP.
- Flyway no autogenera migraciones: revisar el SQL antes de aplicarlo.
- Spring Boot 4 usa `jakarta.*`; los tutoriales antiguos con `javax.*` no sirven.

**Qué NO hacer todavía**
- Rate limiting, Redis, comparador, runner, frontend, Actuator, seguridad de verdad (las claves son de prueba).

---

### FASE P2 — El primer limitador: en memoria

**Objetivo**
Definir la interfaz `RateLimiter` y su primera implementación (contadores en el heap), aplicarla con un filtro a los endpoints, y devolver cabeceras `RateLimit-*` y `429` cuando corresponda. **El sistema empieza a limitar de verdad.**

**Motivación**
Es el limitador más simple y permite aprender el concepto **sin infraestructura**: si no se puede hacer bien en memoria, no se hará bien en Redis. Además establece el **contrato de tests** (exactitud + concurrencia) que las otras dos implementaciones deberán cumplir.

**Nuevos conocimientos**
- **Concurrencia en el JVM**: `ConcurrentHashMap.compute()` y `merge()`, `LongAdder` frente a `AtomicLong`, secciones críticas y el coste real de un lock.
- **El reloj**: por qué `System.currentTimeMillis()` no sirve para medir intervalos (salta con la hora del sistema) y hace falta un reloj monótono **inyectable**, para que los tests no dependan de `Thread.sleep`.
- **Filtros de Spring**: `OncePerRequestFilter`, orden de filtros, cómo se escriben cabeceras y cómo se corta una respuesta.
- **Errores HTTP**: `429 Too Many Requests`, `Retry-After`, y las cabeceras del borrador IETF `RateLimit-Limit` / `-Remaining` / `-Reset`.
- **Intercambiabilidad por configuración**: `@ConditionalOnProperty` para elegir la implementación **sin tocar el código que la usa**.
- **Tests de concurrencia**: `ExecutorService` + `CountDownLatch`, y por qué son deterministas (dependen de un conteo, no de un tiempo).

**Tecnologías**
`spring-boot-starter-webmvc`, JUnit 5, AssertJ, `@WebMvcTest` + MockMvc.

**Implementación**
- `RateLimiter` (interfaz) + `RateLimitRequest` y `RateLimitDecision` (records) — ver sección G.
- `InProcessRateLimiter`: ventana deslizante por clave con `ConcurrentHashMap`, aplicando el peso del endpoint.
- `RateLimitFilter`: extrae la clave, resuelve el escenario de cuota, decide, añade cabeceras y, si toca, responde `429` sin llegar al controlador.
- Selección por configuración: `throttlelab.limiter.type = in-process | postgres | redis`.

**Resultado verificable**
- Con límite 10/s: las 10 primeras → 200 con `RateLimit-Remaining` decreciente; la 11 → 429 con `Retry-After`.
- Pasada la ventana, vuelve a permitir.
- Endpoints de distinto peso consumen cupo distinto.

**Criterios de aceptación**
- **Test de concurrencia**: 100 hilos contra un límite de 10 → **exactamente 10 permitidas y 90 rechazadas**. Es el test que demuestra atomicidad.
- **Test de exactitud**: con un patrón conocido, se permiten exactamente las peticiones del modelo matemático de la ventana.
- Test de cabeceras y de `429` con el formato de error común.
- Test con reloj simulado: instantáneo y determinista, sin `Thread.sleep`.

**Riesgos**
- Limitar **por IP en vez de por clave**: funcionaría en la demo y no probaría nada.
- Usar el reloj equivocado y obtener ventanas erráticas.
- Escribir el estado sin protección y pasar el test de exactitud pero **fallar el de concurrencia**: es justo lo que los dos tests juntos detectan.

**Qué NO hacer todavía**
- Postgres, Redis, métricas, percentiles, runner, frontend.

---

### FASE P3 — El limitador en Postgres

**Objetivo**
`PostgresRateLimiter`: el mismo límite de P2, pero con el estado en una tabla, compartido entre instancias y durable. Más la política de fallo `fail-open` / `fail-closed`.

**Motivación**
Responde a "¿y si no quiero añadir un servicio?". Y enseña, midiendo, por qué la durabilidad **se paga** en latencia: es el primer punto donde la comparación tiene un ganador en el eje del coste y otro en el de la garantía.

**Nuevos conocimientos**
- **Atomicidad en una sentencia**: `INSERT … ON CONFLICT … DO UPDATE … RETURNING`, que resuelve "leer y luego escribir" sin transacción explícita y sin carrera.
- **`SELECT … FOR UPDATE`** y bloqueo por fila: se implementa como variante **a propósito**, para poder **provocar la contención** y medirla.
- **Aislamiento y contención**: qué ocurre cuando muchos hilos escriben en la misma fila; qué son los *lock waits*.
- **Bloat y `autovacuum`**: las escrituras repetidas generan versiones muertas; se ven en `pg_stat_user_tables`.
- **Migración de cubos**: por qué el estado del limitador no puede crecer sin fin y cómo se limpia.
- **`pg_stat_statements`**: medir el coste real de las sentencias en vez de suponerlo.
- **El pool es el techo**: HikariCP, tamaño del pool, y por qué 200 hilos contra un pool de 10 no miden a Postgres, miden el pool.

**Tecnologías**
`JdbcTemplate` para las sentencias atómicas, Flyway, Testcontainers **Postgres** (`spring-boot-testcontainers` + `org.testcontainers:postgresql`).

**Implementación**
- Tabla `rate_limit_bucket` (`key`, `window_start`, `counter`, PK compuesta) con su migración Flyway.
- `PostgresRateLimiter` con `INSERT … ON CONFLICT … RETURNING` en una sola sentencia; variante `FOR UPDATE` seleccionable por configuración para el experimento de contención.
- Barrido de cubos caducados (periódico o `DELETE` en la propia sentencia).
- `failurePolicy` (`OPEN`/`CLOSED`) aplicada cuando la consulta falla, con **test que corta el acceso a Postgres** y verifica ambas políticas.

**Resultado verificable**
- Los mismos tests de exactitud y concurrencia de P2 pasan con este adaptador (misma clase abstracta, otra subclase).
- Cortar Postgres: con `OPEN` las peticiones siguen pasando; con `CLOSED` se rechazan. Ambos casos con test.
- La tabla se estabiliza tras N ventanas en vez de crecer sin límite.

**Criterios de aceptación**
- Exactamente 10 de 100 permitidas, ahora con Testcontainers.
- El barrido deja la tabla estabilizada.
- Contar las sentencias por decisión: **una**, no dos.

**Riesgos**
- Medir con un pool demasiado pequeño y **culpar a Postgres** de un límite que es del pool → se declara en el resultado.
- Olvidar la limpieza de cubos y llenar el disco en una corrida larga.
- Escribir dos sentencias (leer y luego escribir) y reintroducir la carrera que este adaptador debe evitar.

**Qué NO hacer todavía**
- Redis, runner, veredicto, frontend.

---

### FASE P4 — El limitador en Redis

**Objetivo**
`RedisRateLimiter` con **cuatro estrategias** implementadas de forma progresiva: contador con TTL, registro deslizante (ZSET), contador ponderado y token bucket en Lua. Y la demostración, con tests, de en qué se equivoca cada una.

**Motivación**
Redis entra aquí porque **ya existe un problema que resolver** (compartir el límite entre procesos) y porque ya hay dos implementaciones con las que compararlo. Es la fase donde Redis se aprende de verdad: sus estructuras, su atomicidad por scripting y su coste en memoria.

**Nuevos conocimientos**
- **`StringRedisTemplate`** frente a `RedisTemplate` con serializadores, y por qué el serializador por defecto de Java trae problemas.
- **Claves con TTL**: `SET … PX`, `EXPIRE NX`, expiración perezosa, y por qué una clave sin TTL es una fuga de memoria garantizada.
- **Estructuras**: contadores (`INCR`), conjuntos ordenados (`ZADD`, `ZREMRANGEBYSCORE`) y el porqué de cada elección.
- **Scripting Lua**: `DefaultRedisScript`, `EVALSHA`, y **por qué Lua es atómico** (Redis ejecuta el script sin interrupción). Es el punto donde se entiende de verdad qué significa "atómico" en Redis.
- **El coste por estrategia**: O(1) frente a O(n) por clave; `MEMORY USAGE` para verlo con números.
- **Un solo round-trip por decisión**: devolver límite, restante y reinicio en la misma llamada al script. Hacerlo en dos llamadas añade una carrera **además** de latencia.
- **`SCAN` frente a `KEYS`**: por qué `KEYS` no se usa en producción ni para limpiar.

**Tecnologías**
`spring-boot-starter-data-redis` (Lettuce), Redis 7 (Docker), Testcontainers Redis (`com.redis:testcontainers-redis`), scripts `.lua` como recursos del classpath.

**Implementación**
- Estrategia seleccionable por configuración: `fixed_window`, `sliding_log`, `sliding_counter`, `token_bucket`.
- `fixed_window` **primero sin Lua y a propósito**: hay un test que falla porque `INCR` y `EXPIRE` no son atómicos. Se reescribe con Lua y el test pasa. **Ver fallar ese test es el objetivo de aprendizaje de la fase.**
- `sliding_log`: `ZADD` con marca de tiempo, `ZREMRANGEBYSCORE` para descartar lo viejo y `ZCARD` para contar, todo dentro de un script.
- `sliding_counter`: dos contadores y la fórmula ponderada, con su error medido y declarado.
- `token_bucket`: el script completo que devuelve fichas, límite y reinicio. Es la estrategia de referencia.
- `maxmemory` y política configuradas en el compose, para poder demostrar en P9 qué ocurre cuando Redis desaloja claves del limitador.

**Resultado verificable**
- Las cuatro estrategias pasan el mismo test de exactitud (con la tolerancia declarada en el contador ponderado).
- **Test dedicado al borde de la ventana**: con ventana fija, una ráfaga al final de una ventana y otra al principio de la siguiente dejan pasar **el doble** del límite. El test lo documenta como comportamiento esperado de esa estrategia, no como fallo.
- 100 hilos contra límite 10 → exactamente 10 permitidas.

**Criterios de aceptación**
- Un solo round-trip por decisión, verificado contando llamadas.
- **Ninguna clave se queda sin TTL**: test que recorre las claves del limitador y lo comprueba.
- `MEMORY USAGE` por estrategia registrado como dato: `sliding_log` crece con el tráfico; las otras no.

**Riesgos**
- **Creer que `INCR` + `EXPIRE` es atómico.** No lo es, y el test lo demuestra.
- ZSET sin `ZREMRANGEBYSCORE`: la clave crece sin límite y la memoria se dispara.
- Confundir el error **del algoritmo** con el error **del scripting**: son dos ejes distintos (exactitud del modelo y atomicidad de la ejecución) y hay que medirlos por separado.

**Qué NO hacer todavía**
- Redis Cluster, Pub/Sub, Streams, y `redis-benchmark`: lo que se mide es **nuestro** limitador, no la librería.

---

### FASE P5 — El instrumento: el runner

**Objetivo**
El generador de carga en un **proceso aparte sin servidor web**, con warmup, percentiles, los tres modos de medición y distribución uniforme o zipf. Es la única pieza que mide, y se construye **una sola vez**.

**Motivación**
Sin instrumento no hay veredicto, solo intuición. Y un instrumento mal hecho produce conclusiones **peores que no tener ninguna**. Por eso esta fase lleva más rigor que código: el código son unas pocas clases; las reglas son lo difícil.

**Nuevos conocimientos**
- **Generación de carga con *pacing***: no se trata de lanzar peticiones a saco, sino de mantener un ritmo objetivo y medir la **desviación respecto al plan**. Si el generador no puede seguir el ritmo, **eso también es un dato** y hay que reportarlo, no esconderlo.
- ***Coordinated omission***: el error clásico y silencioso de los benchmarks de latencia. Si el generador solo mide las peticiones que **consiguió** enviar, las que se retrasaron no cuentan y la latencia sale artificialmente baja. Se corrige midiendo desde el instante en que la petición **debía** haberse enviado. Es el concepto que separa un benchmark honesto de uno de blog.
- **Percentiles de verdad**: histograma de latencias. Se usa **HdrHistogram** (el que usan JMH y los benchmarks serios) y se entiende por qué no basta con media y desviación típica.
- **Distribución zipf**: generar claves con sesgo realista, no uniforme.
- **Virtual threads (Java 21)**: concurrencia masiva sin un hilo del sistema por petición, y cuándo conviene frente a un pool clásico.
- **Instrumentar el propio instrumento**: cuánto consume el generador (CPU, GC) para poder afirmar si él fue el cuello de botella.

**Tecnologías**
`java.net.http.HttpClient` del JDK (menos capas entre el generador y lo medido que un cliente de más alto nivel), `ExecutorService` y virtual threads, **HdrHistogram** (versión explícita: no está en el BOM de Spring Boot), Jackson.

**Implementación**
- `LoadProfile` (record): rps objetivo, claves activas, distribución, duración, warmup, mezcla de endpoints y modo de medición.
- `LoadGenerator`: mantiene el ritmo, mide **latencia planificada** además de la real (anti-*coordinated omission*), y cuenta permitidas y rechazadas.
- `LatencyRecorder`: HdrHistogram + extracción de p50/p95/p99/p99.9 y máximo.
- `RunResult`: cifras + entorno + configuración + commit del código.
- Los tres modos: **M1** una conexión y una petición a la vez; **M2** N conexiones concurrentes; **M3** el perfil descrito por quien usa la herramienta.
- **Regla de oro del diseño: el camino medido es el mismo para las tres implementaciones.** Todo pasa por HTTP contra la API y por el filtro real, porque medir unas por HTTP y otras en proceso sería comparar cosas distintas. El sobrecoste de HTTP y de la JVM es **constante para las tres** y por tanto se cancela en la comparación; lo que no se cancele, se declara.

**Resultado verificable**
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=runner` genera carga y **no abre ningún puerto**.
- Con límite 100/s y carga de 100 rps: ~100 % permitidas. Con carga de 300 rps: ~1/3 permitidas y 2/3 rechazadas con `429`.
- La duración y el warmup se respetan; a 0 rps no se envía nada.
- M1 y M2 dan latencias visiblemente distintas, y **las dos se reportan**.

**Criterios de aceptación**
- **Test de pacing**: las peticiones enviadas difieren del plan en menos del margen declarado.
- **Test de percentiles**: sobre una muestra de distribución conocida, los percentiles calculados coinciden con el valor esperado.
- **Test anti-*coordinated omission***: si el servidor se vuelve lento a partir de la petición N, el p99 **detecta** el retraso (un generador sin corrección lo escondería).
- El modo de medición se guarda en el resultado y **el sistema impide comparar corridas de modos distintos**.

**Riesgos**
- **Que el generador sea el cuello de botella** → se instrumenta y se reporta su propio consumo; si satura, se dice.
- **Coordinated omission** → es *el* riesgo de esta fase, con test específico.
- Que la API y el runner acaben en la misma máquina sin declararlo → el perfil `runner` existe precisamente para impedirlo por descuido.
- Usar promedios "porque se leen mejor" → prohibido por el principio rector 5.

**Qué NO hacer todavía**
- Emitir conclusiones automáticas (eso es P6 y P7), UI, y Redis a fondo (P9).

---

### FASE P6 — La corrida como dato

**Objetivo**
Persistir cada corrida —perfil de carga, resultados, entorno y commit— y poder **repetir** cualquiera de ellas. La comparación deja de ser un número en una consola y pasa a ser un registro reproducible.

**Motivación**
Un benchmark sin trazabilidad no vale nada: dentro de tres meses nadie sabrá con qué configuración se obtuvo ese número, y las conclusiones del README se quedarán sin respaldo. Esta fase convierte el resultado en un **dato citable**.

**Nuevos conocimientos**
- **Modelado de un experimento**: qué se guarda como entidad y qué como JSONB. El perfil y las cifras son documentos que se escriben una vez y se leen enteros; no se consultan por campo.
- **Repetibilidad**: una corrida se puede relanzar con la misma semilla, el mismo perfil y el mismo dataset, y el sistema **sabe** si las condiciones cambiaron.
- **Azar controlado**: generación de claves reproducible (misma semilla, mismo patrón zipf).
- **Histórico y comparación**: agrupar corridas comparables y detectar cuándo dos corridas **no** lo son (modo, entorno o commit distintos).
- **Progreso en vivo**: cómo publicar el avance de una corrida a los clientes sin que el runner tenga que hablar con nadie.

**Tecnologías**
JPA + Flyway + JSONB, `@JdbcTypeCode(SqlTypes.JSON)`, `@ConfigurationProperties`.

**Implementación**
- Entidad `BenchRun` (`id`, `profile` JSONB, `environment` JSONB, `result` JSONB, `mode`, `status`, `createdAt`, `finishedAt`, `commitSha`, `seed`). Serie temporal dentro del JSONB del resultado, salvo que se necesite granularidad por muestra.
- `GET /v1/runs`, `GET /v1/runs/{id}`, `POST /v1/runs` (crea la corrida y la encola para el runner), `POST /v1/runs/{id}/repeat`.
- `RunComparator` que **impide** comparar modos distintos y avisa cuando el entorno o el commit difieren.
- Endpoint de progreso `GET /v1/runs/{id}/stream` (SSE), que en esta fase puede servirse leyendo la fila y en P8 se conecta a la UI.

**Resultado verificable**
- Lanzar una corrida desde `curl` y verla registrada con su perfil, su entorno y sus cifras.
- `POST /v1/runs/{id}/repeat` la relanza con el mismo perfil y semilla.
- Intentar comparar una corrida M1 con una M3 devuelve un error claro, no un número.

**Criterios de aceptación**
- Toda corrida guarda: perfil, modo, semilla, cifras, entorno (CPU, RAM, versiones, `maxmemory` de Redis, tamaño del pool) y commit.
- Repetir una corrida con la misma semilla reproduce el perfil exactamente (con test).
- El esquema está en Flyway, con `ddl-auto: validate`.

**Riesgos**
- Guardar el resultado en columnas sueltas hasta acabar con una tabla de 40 campos → el perfil y las cifras van en JSONB; solo lo que se filtra (`mode`, `status`, `commitSha`, `seed`) son columnas.
- Confundir "repetir" con "reproducir exactamente": se repite el **experimento**, las cifras variarán. El sistema no debe prometer lo que no puede cumplir.

**Qué NO hacer todavía**
- Automatizar conclusiones (P7) y UI (P8).

---

### FASE P7 — El veredicto

**Objetivo**
Convertir las cifras de una corrida en una **conclusión argumentada** y descargable: ganador por eje, evidencia numérica y criterios no medibles, siempre en bloques separados.

**Motivación**
Es el producto. Una tabla de números obliga al lector a interpretarla; el valor de la herramienta es **decir qué significa** y con qué límites, citando las cifras de *esa* corrida. Y decir también **cuándo no merece la pena Redis**, que es la mitad del objetivo.

**Nuevos conocimientos**
- **Reglas de decisión explícitas**: umbrales escritos en código revisable, no en la cabeza de quien mira el gráfico. Se pueden discutir y rebatir.
- **Separar lo medido de lo no medido**: "Redis fue 3× más rápido" y "pero no tienes Redis en tu stack" son bloques distintos, y el informe lo dice así.
- **Honestidad estadística**: si la diferencia entre dos implementaciones cae dentro de la dispersión de las repeticiones, la conclusión es **"no se distinguen con esta carga"**, no un ganador elegido por un decimal.
- **Informe reproducible**: Markdown generado con las cifras y el identificador de la corrida, descargable y pegable en el README.

**Tecnologías**
Java (lógica de reglas, testeable de forma pura), Jackson, plantillas de texto para el Markdown.

**Implementación**
- `VerdictEngine`: recibe un `RunResult` y devuelve un `Verdict` (record) con ganadores por eje, evidencia y advertencias.
- Reglas, entre otras: *diferencia por debajo de la dispersión → "no se distinguen"*; *`remaining` incoherente o eviction detectada → advertencia de exactitud*; *contención creciente en Postgres → advertencia de escalado*.
- Bloque fijo de **criterios no medibles** (sección C.4), que se imprime siempre sin depender de las cifras.
- `GET /v1/runs/{id}/report` en Markdown, con cifras y el identificador de la corrida.

**Resultado verificable**
- Sobre una corrida real, el informe dice quién gana en latencia, throughput, exactitud y coste de recursos, y cita las cifras que lo justifican.
- Con dos implementaciones estadísticamente indistinguibles, el informe dice que **no se distinguen**.
- Cuando toca, incluye la frase honesta: *"con tu perfil, Redis no se paga"*.

**Criterios de aceptación**
- El motor de reglas se testea **sin infraestructura**: se le pasan `RunResult` construidos a mano y se verifica la conclusión. Debe haber un caso en el que gane la memoria y otro en el que gane Redis.
- Ninguna conclusión se imprime sin la cifra que la respalda.
- El informe nombra el identificador de la corrida y el modo de medición.

**Riesgos**
- **Sesgo de confirmación**: escribir reglas que siempre favorezcan a Redis. Se combate con el caso de test obligatorio en el que **gana la memoria**, y con el principio rector 2.
- Convertir el veredicto en una caja negra → las reglas deben ser legibles y estar documentadas.
- Prometer generalidad: el veredicto vale para el perfil medido, y el informe lo dice explícitamente.

**Qué NO hacer todavía**
- Un LLM que "explique" el informe: la conclusión es determinista y sale de reglas. Aquí un modelo solo añadiría imprecisión disfrazada de prosa.
- UI (P8).

---

### FASE P8 — Frontend

**Objetivo**
Las tres pantallas: claves y cuotas, comparador con progreso en vivo, e informe. Detalle de UX suficiente para que la herramienta se pueda usar sin `curl`.

**Motivación**
Ya se domina Next/React, así que esta fase es rápida y puramente de producto. Va al final por una razón estructural: el contrato (rutas, payloads y eventos) ya está **congelado y probado** por los tests de P1-P7, así que la UI es un cliente fino y no hay que reescribirla cuando cambie el backend.

**Nuevos conocimientos**
- **Next.js App Router** para el flujo del comparador (formulario → corrida en curso → resultado con identificador propio).
- **SSE en el navegador** con `EventSource`: reconexión automática, eventos nombrados, y el caso crítico de **conectar cuando la corrida ya ha terminado** (el servidor debe enviar el estado actual al abrir; si ya acabó, cierra enseguida).
- **Visualización de percentiles**: por qué un gráfico de barras del promedio es un engaño y hay que mostrar la distribución (p50/p95/p99 y dispersión).
- **Estado asíncrono en React**: carreras entre el evento del stream y el `fetch` inicial, y cómo resolverlas.
- **Degradación y errores**: qué se muestra cuando el stream se cae, cuando el runner no está arrancado, o cuando Redis no responde.

**Tecnologías**
Next.js 16 (App Router, TypeScript, Tailwind 4), `EventSource`, `@tanstack/react-query` para el estado del servidor.

**Implementación**
- `/` — estado del sistema: ¿está la API viva?, ¿están Postgres y Redis?, ¿cuál es la implementación activa?
- `/keys` — crear, listar y revocar claves y escenarios; muestra el `429` en directo al pasarse de cuota.
- `/compare` — describir el perfil, lanzar la corrida, seguirla en vivo y ver el resultado.
- `/runs` — histórico, con filtro por modo y aviso de "no comparable" cuando el modo o el entorno difieren.
- `/runs/[id]` — informe con el veredicto y botón de descarga.
- **Regla no negociable: la UI no mide.** No cuenta peticiones ni cronometra nada. Solo muestra lo que el servidor ya midió (principio rector 3).
- `.env.local` con la URL de la API, y **petición directa a la API** (`:8000`) en lugar de *proxear* el SSE por un rewrite de Next: el buffering del proxy rompe los streams.

**Resultado verificable**
- Desde la UI, crear una clave, ver cómo se agota su cupo y cómo responde `429` con las cabeceras.
- Lanzar una corrida con las tres implementaciones, ver el progreso en vivo y el resultado comparado.
- Refrescar la página a mitad de una corrida: la UI se reconecta y muestra el estado correcto.
- Abrir el informe de una corrida antigua y descargarlo en Markdown.

**Criterios de aceptación**
- **Ningún componente de la UI realiza mediciones**: se verifica revisando que no hay contadores ni temporizadores en el cliente.
- La página de una corrida inexistente muestra 404 con salida clara.
- El comparador muestra la dispersión, no solo un número por eje.
- Tests de los componentes clave con Vitest + Testing Library: formulario de perfil, mapeo de estados, montaje del gráfico con datos fijos y el caso "sin datos".
- Aviso en el README: Vitest **no** soporta *Server Components* asíncronos; se testean componentes de cliente con lógica y el resto se cubre con el smoke de P10.

**Riesgos**
- **Sobrediseñar la UI.** Es una herramienta: claridad y densidad de datos por encima de estética.
- **Medir en el cliente** por comodidad (contar en el navegador es más fácil que instrumentar el servidor) → prohibido; es el error que invalidaría todo el laboratorio.
- Gráficos que sugieran conclusiones que los datos no sostienen (barras de promedios, ejes truncados).

**Qué NO hacer todavía**
- Escritorio, temas oscuros elaborados, colaboración, autenticación de usuarios, deployment público.

---

### FASE P9 — Redis por dentro (resumen)

Instrumentar el interior de Redis durante las corridas: `INFO` (memoria, keyspace, `evicted_keys`), `SLOWLOG`, `MEMORY USAGE`, fragmentación, eviction y picos por `BGSAVE`/AOF.

- **Verificable**: forzar `BGREWRITEAOF` y **ver el pico en el p99**; bajar `maxmemory` hasta que haya eviction y ver la **exactitud deteriorarse**; con pipelining sube el throughput.
- **Riesgo principal**: instrumentar dentro del camino caliente invalida la medición. La captura va fuera del runner.

### FASE P10 — Docker, smoke y demo (resumen)

Cinco servicios con un solo comando: `docker compose up --build`.

- `api` y `runner` salen de **la misma imagen** con distinto `command`; Next *standalone*; healthchecks con `depends_on: service_healthy`; `scripts/smoke.ps1`; CI; demo grabada; README de portfolio.
- **Verificable**: cinco servicios `healthy`; smoke verde dos veces seguidas; `down -v` y arrancar limpio sin pasos manuales.
- **Riesgo principal**: que la demo corra en Docker y las conclusiones se midieran en nativo → se declara, porque cambia la latencia.

---

## Plan de tests

| Nivel | Herramienta | Qué cubre |
|---|---|---|
| Unitario | JUnit 5 + AssertJ, sin Spring | reglas del veredicto, percentiles, perfil de carga, validación, hashing de claves |
| Concurrencia | `ExecutorService` + `CountDownLatch` | 100 hilos contra límite 10 → **exactamente 10 permitidas** (×3 implementaciones) |
| Exactitud | mismo contrato abstracto ×3 subclases | cada algoritmo permite lo que dice su modelo |
| Web (slice) | `@WebMvcTest` + MockMvc + **`@MockitoBean`** | rutas, validación, errores, cabeceras, `429` |
| Persistencia (slice) | `@DataJpaTest` + Testcontainers Postgres | mapeos, JSONB, Flyway desde esquema vacío |
| Integración | Testcontainers Postgres + Redis | adaptadores reales, `fail-open`/`closed`, eviction, TTL |
| Sistema | `@SpringBootTest(RANDOM_PORT)` | `POST` → SSE → resultado → informe |
| Frontend | Vitest + Testing Library | formulario, mapeo de estados, gráficos con datos fijos |
| Smoke | `scripts/smoke.ps1` | arranque completo con un comando |

**Reglas:** `mvn clean verify` siempre (nunca `mvn compile`); los tests de integración llevan `@Tag("integration")` y quedan excluidos por defecto; ninguna medición real depende de la red; `@MockitoBean` y **no** `@MockBean` (eliminado en Spring 7 / Boot 4).

## ADRs (`docs/decisions`)

- `0001` Un solo proyecto con dos perfiles: `api` y `runner`.
- `0002` Tres implementaciones detrás de una interfaz `RateLimiter` (y por qué aquí sí se justifica).
- `0003` Redis es efímero y prescindible; los resultados viven en Postgres.
- `0004` Rigor del banco de pruebas: warmup, percentiles, modos M1/M2/M3, *coordinated omission*, entorno declarado.
- `0005` La UI no mide: la medición vive en el servidor.
- `0006` Herramienta, no producto: sin precios, sin clientes, sin deployment público.

## Apéndice — lo que se evaluó y se descartó

**Redis como cola de trabajos (Streams, consumer groups, PEL, `XAUTOCLAIM`).** Se evaluó repartir trabajo entre procesos y se descartó: este laboratorio no reparte trabajo, aplica límites. El reparto atómico (`XREADGROUP`), la lista de pendientes (PEL) y la recuperación de un worker caído (`XAUTOCLAIM`) solo tienen sentido con workers persistentes, y aquí no los hay. Si algún día se añade una cola, ese diseño merece su propio ADR.

**Redis como caché de resultados.** Igual de innecesario: los resultados se escriben una vez y se leen enteros, y Redis puede desalojarlos o vaciarse.

## Qué NO es este proyecto

- No es un servicio que se venda, ni un producto con clientes y precios.
- No es un benchmark de librerías: **`redis-benchmark` no se usa**. Se mide **nuestro** limitador.
- No es una comparación general Redis contra Postgres: es **una decisión concreta** (limitar peticiones) medida bajo perfiles concretos.
- No hay deployment público, ni cluster, ni multi-nodo.

## Riesgos globales

1. **Degenerar en benchmark sintético** → cada escenario es un servicio real; la comparación es una vista, no el producto.
2. **Scope**: once fases es mucho. Cada fase deja el sistema funcionando y, si hay que recortar, se recorta **P9**; nunca P5-P7, porque sin instrumento no hay producto.
3. **El entorno contamina la medición** (Docker sobre Windows) → se declara en cada corrida.
4. **Sesgo hacia Redis** al escribir las reglas del veredicto → hay un test obligatorio en el que **debe ganar la memoria**.
5. **No terminar**, el riesgo número uno de un proyecto-aprendizaje → fases con gate y un entregable demostrable en cada una.
