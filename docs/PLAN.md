# RepoVisor — Plan de desarrollo

> **RepoVisor** (antes *CodeMapper*). Web app que analiza repositorios públicos de GitHub y genera un mapa interactivo de arquitectura y un documento de onboarding.
>
> Objetivo personal: demostrar experiencia práctica real como **Software / Full-Stack / Product / AI-oriented Engineer**, aprendiendo de verdad **Java moderno, Spring Boot, Redis Streams, background jobs y sistemas asíncronos**. NO es un proyecto de Data/DevOps/ML/Backend.
>
> **Sin deployment público.** RepoVisor es una aplicación full-stack que corre íntegramente en **local con Docker Compose**, con tests, y que se demuestra mediante una **demo grabada en vídeo** y un README de portfolio. MVP en **4–6 semanas** por una sola persona.

---

## Cómo usar este plan

- Trabajamos **fase por fase**, en orden. Nunca se salta una fase.
- Antes de implementar cada fase: 1) entender el objetivo, 2) revisar las tecnologías nuevas, 3) definir exactamente qué se construye, 4) implementar, 5) probar, 6) verificar los criterios de aceptación.
- Cada fase produce **una parte funcional del producto** y es verificable antes de continuar.
- Cuando una fase propone varias opciones, hay una **recomendación** con su justificación (aprendizaje, simplicidad, coste, mantenibilidad, valor de entrevista).
- Tu perfil ya cubre Next/React/TS/Node/PostgreSQL/JWT/SSE/testing/deploy. **El aprendizaje nuevo se concentra en Java moderno, Spring Boot, Redis Streams, background jobs, async y composición local multi-servicio con Docker Compose.** No repetimos lo que ya dominas salvo cuando el flujo lo requiere.

Principio arquitectónico rector (no negociable):

```text
Code analysis → descubre hechos (programático, determinista)
AI           → interpreta, resume, explica, documenta
```

**Dato técnico que condiciona el plan**: desarrollo en Windows → Redis y el worker no corren nativos. La solución no es pelear con el sistema, es usar Docker como entorno de desarrollo para esos servicios desde la Fase 7. Docker pasa de "fase final" a **necesidad en F7**; en F11/F12 se convierte en el **entorno reproducible que define el entregable final** del proyecto (nada de deployment público).

---

# A. Arquitectura final propuesta

```text
┌───────────────────────┐
│  Next.js (web)        │  Formulario, polling de estado, mapa React Flow, onboarding
│  React + Tailwind     │  @xyflow/react v12
└──────────┬────────────┘
           │  HTTP / REST (local)
┌──────────▼────────────┐
│  Spring Boot (api)    │  POST /analyses → crea job · GET /analyses/{id} → estado
│  Spring MVC           │  GET …/architecture · GET …/documents · rate limit
│  Spring Data JPA      │
└──────┬──────────┬─────┘
       │          │  enqueue (Redis Streams)
       │          └──────────► ┌───────────────┐
       │                       │  Redis        │  · Stream (job queue)
       │                       │               │  · rate limiting
       │                       └──────┬────────┘
       │                              │  Stream consumer (Spring Boot worker)
       │                       ┌──────▼──────────────────────────────┐
       │                       │  Spring Boot Worker                  │
       │                       │  clone (tarball) → scan → deps →    │
       │                       │  module graph (JavaParser / tree-   │
       │                       │  sitter) → AI enrichment (onboarding,│
       │                       │  best-effort, cacheado, degradable)  │
       │                       └──────┬──────────────────────────────┘
       │                              │
       └──────────────► ┌─────────────▼─────────────┐
                        │  PostgreSQL               │  fuente de verdad:
                        │  analyses + artefactos    │  estado, structure, modules,
                        │  (JSONB)                  │  edges, deps, ai_docs
                        └───────────────────────────┘
```

Componentes y responsabilidades:

| Componente | Proceso | Responsabilidad |
|---|---|---|
| `web` | Next.js | Producto: formulario, estados del análisis, mapa, onboarding. Sin lógica de negocio. |
| `api` | Spring Boot (`--spring.profiles.active=api`) | Contrato REST, validación, creación de jobs, consulta de resultados. No analiza nada. |
| `worker` | Spring Boot (`--spring.profiles.active=worker`) | Todo el análisis determinista + AI enrichment. Un proceso, pipeline por etapas. |
| `redis` | Redis | Stream de jobs (job queue) + rate limiting. **No es fuente de verdad** de estado. |
| `postgres` | PostgreSQL | Fuente de verdad: estado del análisis y todos los artefactos generados. |

Decisiones que cierran la arquitectura:

1. **La fuente de verdad del estado es Postgres**, no Redis. Redis guarda la cola; el estado de negocio (queued → completed/failed) vive en persistencia. Así el estado sobrevive reinicios del worker o de Redis.
2. **`api` y `worker` son dos procesos del MISMO proyecto Spring Boot** (un solo `pom.xml`, dos profiles `api`/`worker`). Razón: comparten 100% de modelos de dominio, funciones de análisis, jobs y acceso a DB. Separarlos en dos módulos solo añade plomería en el MVP. En el stack local se empaqueta **una** imagen Docker con dos comandos (`java -jar app.jar --spring.profiles.active=api` / `worker`), y la orquestación con Compose reproduce la composición de procesos del sistema completo.
   - *Alternativa* (anotada, no recomendada para MVP): dos módulos Maven + paquete compartido. Más "enterprise", más fricción. Se puede refactorizar después si hay necesidad real.
3. **Artefactos de análisis en columnas JSONB** (structure, modules, edges, dependencies, ai_docs) en lugar de tablas relacionales finas. Razón: se escriben una vez, se leen enteros, nunca se consultan por campo. JSONB con `@JdbcTypeCode(SqlTypes.JSON)` es más simple y perfectamente defendible. Normalizar sería sobre-modelar ahora.
4. **Se elimina el transporte vía git clone por SSH/HTTPS** como fuente principal: descarga por **tarball** (codeload.github.com) sin binario git en la imagen. Más rápido, más determinista, y sin credenciales. Un transporte **local** (carpeta del repo) se añadirá en F12 para tests y demo sin red.
5. **El worker se ejecuta en Docker (imagen Linux) incluso en desarrollo.** Redis tampoco corre nativo en Windows. Es la decisión que hace viable el plan en tu máquina.

---

# B. Roadmap por fases (13 fases)

```
F1   Fundación: JDK, Maven, monorepo y primer Spring Boot
F2   Frontend mínimo: formulario + polling de estado
F3   Java + Spring Boot REST API: persistencia con JPA + Flyway
F4   Análisis determinista #1: clone + scan + JavaParser          ← análisis sincrónico en API
F5   Análisis determinista #2: tree-sitter + dependencias
F6   Visualización interactiva con React Flow                     ← primer mapa real
F7   Pipeline asíncrono: Redis Streams + worker Spring Boot       ← Docker entra aquí ⭐
F8   AI enrichment: onboarding + explicaciones (best-effort)      ⭐
F9   Robustez: retries, idempotencia, rate limiting
F10  Testing y observabilidad
F11  Docker: entorno local reproducible (compose)                 ← Docker se completa
F12  Reproducibilidad: bootstrap limpio, transport local y smoke
F13  Pulido, demo grabada y versión portfolio
```

**Nota sobre el orden** (respecto a tu progresión de referencia): el análisis corre **sincrónicamente** en la API hasta F7, cuando se extrae a un worker asíncrono con Redis Streams. Esto permite aprender Java y el análisis de código sin mezclar async al principio. La desventaja es que la API bloquea durante el análisis (aceptable para repos pequeños en desarrollo). La ventaja es que en F7 el cambio a async es un refactoring motivado: *"ya sabemos qué hace el análisis; ahora aprendamos a ejecutarlo sin bloquear la petición HTTP"*.

La **visualización (F6) sale después de tener el grafo (F4+F5)** porque el mapa necesita datos reales. No hay razón técnica para visualizar datos fake cuando el pipeline determinista produce datos reales en F4.

---

## Detalle por fase

Cada fase incluye: Objetivo · Motivación · Nuevos conocimientos · Tecnologías · Implementación · Resultado verificable · Criterios de aceptación · Riesgos · Qué NO hacer todavía.

---

### FASE 1 — Fundación: JDK, Maven, monorepo y primer Spring Boot

**Objetivo**
Dejar preparado el entorno de desarrollo Java y el repositorio de trabajo: JDK 21, Maven, estructura multi-app, Spring Boot hello world, Docker Compose con Postgres y Redis, y decisiones de diseño documentadas.

**Motivación**
El resto de fases tropiezan sin una base común clara. Instalar Java/Maven y configurar el monorepo desde el inicio evita fricciones persistentes. Es barato si se hace ahora y cara si se hace tarde. Docker Compose con Postgres+Redis se anticipa a F3 (necesita Postgres) y F7 (necesita Redis).

**Nuevos conocimientos**
- **JDK 21 LTS**: instalación en Windows, `JAVA_HOME`, `PATH`, versiones LTS vs feature.
- **Apache Maven**: `pom.xml`, dependencias, plugins, `spring-boot-starter-*`, Maven Wrapper.
- **Spring Boot**: `@SpringBootApplication`, `@RestController`, `@GetMapping`, autoconfiguration, `application.yml`.
- Convenciones de un monorepo de servicios: una app por proceso, invariantes de `.gitignore`, comandos reproducibles.
- **Spring Profiles**: `application-api.yml`, `application-worker.yml` — los dos entrypoints del mismo proyecto.

**Tecnologías**
- Eclipse Temurin **JDK 21 LTS** (u otra distribución LTS).
- Apache **Maven 3.9.x** (o Maven Wrapper).
- **Spring Boot 4.1.x** (OSS actual, soporta Java 17–26). *Nota: mencionaste 3.3.x inicialmente, pero 3.5.x ya está EOL desde junio 2026. Se recomienda 4.x para un portfolio de 2026.*
- `create-next-app` con TypeScript + Tailwind — ya dominado, sirve de esqueleto.
- Git (init + push a GitHub). Repo público desde el inicio.

**Implementación**
Estructura base:

```
repovisor/
  apps/
    web/          # Next.js (React + Tailwind + TypeScript)
    backend/      # Spring Boot (Java 21, Maven) — API + Worker (dos profiles)
      pom.xml
      mvnw / mvnw.cmd
      src/main/java/com/repovisor/
        RepovisorApplication.java
        api/
          AnalysisController.java   # @RestController, GET /healthz
      src/main/resources/
        application.yml
        application-api.yml
        application-worker.yml
  docs/
    PLAN.md        # este documento
    decisions/     # ADR ligeros (1 por decisión importante, textual, breve)
  .github/workflows/  # (esqueleto; CI real en F10)
  .gitignore
  README.md
```

Comandos relevantes (Windows/PowerShell):
- Instalar JDK: `winget install EclipseAdoptium.Temurin.21.JDK` (o descarga manual desde adoptium.net).
- Instalar Maven: `winget install Apache.Maven` (o descarga manual; añadir a `PATH`).
- Verificar: `java -version`, `mvn -version`.
- Crear el proyecto Spring Boot con **Start Spring IO** o Spring Initializr (bootstrap), o `pom.xml` a mano con `spring-boot-starter-web`.
- `mvn spring-boot:run` → endpoint `GET /healthz` en `http://localhost:8000`.
- `npx create-next-app@latest apps/web` (App Router, TypeScript, Tailwind).
- `docker compose up -d` con compose mínimo (postgres, redis, healthchecks).
- `git init`, `git add`, `git commit`, push a GitHub (repo público).

**Resultado verificable**
- `mvn spring-boot:run` arranca la API y `GET /healthz` devuelve `{"status": "ok"}` en `http://localhost:8000`.
- `apps/web` sirve la página por defecto en `http://localhost:3000`.
- `docker compose up -d` levanta Postgres (puerto 5432) y Redis (puerto 6379) con healthchecks verdes.
- `mvn compile` y `mvn package -DskipTests` pasan sin errores.
- Repo subido a GitHub; README con comandos de desarrollo.

**Criterios de aceptación**
- Se puede clonar el repo en una máquina limpia con JDK 21 + Maven + Docker y arrancar api y web con los comandos documentados (menos de 5 pasos).
- El profile `worker` arranca sin errores (aunque no haga nada todavía).
- Las decisiones anotadas como "ADR" en F1 quedan escritas: layout monorepo, api+worker mismo proyecto, JSONB para artefactos, Postgres como fuente de verdad de estado, transporte por tarball.

**Riesgos**
- Problemas de `JAVA_HOME` / `PATH` en Windows → documentar explícitamente y verificar con `java -version` y `mvn -version`.
- Sobrediseñar el monorepo (workspaces npm, paquetes compartidos). Antídoto: un solo app web, un proyecto Spring Boot, zero fricción innecesaria.
- Dispersarse con la configuración de Spring Boot/Next.js. No: es esqueleto.
- Retrasarse escribiendo README/política del repo. README corto.

**Qué NO hacer todavía**
- JPA, Flyway, modelos de dominio, análisis, Redis Streams, AI, auth.
- Tests formales, CI, contratos de API definitivos.
- Definir el schema de la API de diseño: solo el `GET /healthz` de vida.

---

### FASE 2 — Frontend mínimo: formulario + polling de estado

**Objetivo**
Primera experiencia usable: el usuario pega una URL, se crea el análisis y la UI muestra el progreso por polling. El backend devuelve datos mock (el API real llega en F3).

**Motivación**
El producto es asíncrono; la UX de "estado avanzando" es el corazón del anteproyecto. Construir el frontend ahora (ya lo dominas) da feedback inmediato y fuerza el diseño del contrato web↔API antes de complicar el backend. Es un "win rápido" antes de sumergirte en Java.

**Nuevos conocimientos**
- Enrutado de Next.js App Router para flujos de 2 niveles (home + página de detalle con id dinámico).
- Política de fetch client-side, estados de carga/error/vacío, retry y `refetchInterval` (degradado a manual).
- Por qué **polling ahora y no SSE**: mecánica ya dominada por ti, destruye complejidad de transporte; SSE queda reservado como mejora de producto post-MVP (F13). Es una decisión consciente, documentable en entrevista.

**Tecnologías**
- `@tanstack/react-query` (o fetch + estado manual; se recomienda react-query por su gestión de polling/caché/retry). Si prefieres control total, fetch manual es válido; elige uno y sé consistente.
- Tailwind para el esqueleto; sin librería de UI todavía.

**Implementación**
- Página `/` (home): input de URL + botón "Analizar" → `POST /api/v1/analyses` → redirige a `/analyses/[id]`.
- Página `/analyses/[id]`: muestra repositorio, badge de estado, etapa actual (queued → cloning → scanning → analyzing_deps → building → completed → failed), último error si `failed`, y refresco automático cada 2–3 s. Zona "placeholder" donde entrarán el mapa y el onboarding.
- Página `/analyses`: listado de análisis previos (reutiliza `GET /api/v1/analyses`), clic abre uno. Es barata ahora y ya da valor de producto.
- Estados: loading, error de red, 400/404 con copia clara, botón reintentar manual.
- Backend mock: el controller devuelve una lista en memoria (no persistida) o datos hardcodeados. La persistencia real llega en F3.
- `.env.local` con la URL de la API (proxy Next opcional a `/api` propio del backend, decisión mínima).

**Resultado verificable**
- Flujo: pegar `https://github.com/owner/repo` → se crea → navega a detalle → el badge muestra `queued` (o la etapa que corresponda) y se actualiza solo.
- El listado permite reabrir análisis previamente creados.
- Con un `POST` mock que devuelve `{id, status:"completed"}` en 2 s, la UI muestra la transición de estado.

**Criterios de aceptación**
- Las 3 pantallas (form, detalle, listado) navegan sin errores con datos mock de la API.
- La página de detalle de un id inexistente muestra 404 con opción de volver.
- Los estados del pipeline (queued/cloning/scanning/analyzing_deps/building_architecture/completed/failed) ya se mapean a labels visibles aunque aún no lleguen del worker.

**Riesgos**
- Sobrediseñar UI (componentes, animaciones, tema) demasiado pronto. La UI de verdad llega en F6/F13.
- Estado asíncrono mal gestionado en React (carreras, updates fuera de orden). React Query mitiga la mayor parte.

**Qué NO hacer todavía**
- React Flow, SSE, mapa, onboarding, auth, persistencia local ni caché offline.

---

### FASE 3 — Java + Spring Boot REST API: persistencia con JPA + Flyway

**Objetivo**
Construir la API con persistencia real: modelo `Analysis` como entidad JPA, migraciones Flyway, endpoints CRUD (crear y consultar estado), y configuración 12-factor. El frontend de F2 se conecta a datos reales.

**Motivación**
Nada de lo siguiente funciona sin un lugar donde persistir el estado del análisis. Postgres es la fuente de verdad de todo el producto; Spring Boot es el contrato público. Esta fase es donde aprendes Java/Spring de verdad: DI, JPA, Flyway, profiles, configuración.

**Nuevos conocimientos**
- **Java moderno esencial**: records (DTOs), text blocks, pattern matching, `Optional`, streams, `List.of()`/`Map.of()`, varargs.
- **Spring Boot en serio**: `@Component`, `@Service`, `@Repository`, construction injection (sin `@Autowired` en campos), `@ConfigurationProperties`, profiles, `@Profile("api")` vs `@Profile("worker")`.
- **Spring Data JPA**: `JpaRepository`, `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column(columnDefinition = "jsonb")`, `@JdbcTypeCode(SqlTypes.JSON)`, `@Enumerated`, `Optional<T>`, query methods.
- **Flyway**: `V1__create_analysis.sql`, migraciones incrementales reproducibles.
- **application.yml**: configuración 12-factor por variables de entorno, perfiles, datasources.
- Columnas **UUID** y **JSONB** en Postgres desde Java.
- Modelado de un **estado finito con enum**: `AnalysisStatus` (QUEUED → CLONING → SCANNING → ANALYZING_DEPS → BUILDING_ARCHITECTURE → COMPLETED | FAILED).

**Tecnologías**
- `spring-boot-starter-web`, `spring-boot-starter-data-jpa`.
- `postgresql` (runtime), `flyway-core`, `flyway-database-postgresql`.
- Jackson (viene con Spring Boot) para serialización JSONB.
- (Opcional) `springdoc-openapi` para Swagger UI; si no, Insomnia/curl bastan.

**Implementación**
- Entidad `Analysis`: `id` (UUID), `repoUrl`, `owner`, `name`, `status` (enum), `stage` (enum), `error` (nullable), `createdAt`, `updatedAt`, más columnas JSONB vacías que se rellenarán en fases posteriores (structure, modules, edges, dependencies, meta, aiDocs).
- `AnalysisRepository extends JpaRepository<Analysis, UUID>`.
- `AnalysisService` con lógica de creación y consulta.
- `AnalysisController`:
  - `POST /api/v1/analyses` — valida la URL de GitHub (regex + formato `owner/repo`), crea fila con status `QUEUED`. Devuelve `201/202`.
  - `GET /api/v1/analyses/{id}` — devuelve análisis con estado y etapa actual.
  - `GET /api/v1/analyses` — lista de los últimos N con paginación simple.
- Migración Flyway `V1__create_analysis.sql` (tabla `analysis`).
- `application.yml` con datasource, JPA (ddl-auto `validate`), Flyway, profiles.
- DTOs con records Java; mapeo con un mapper simple (sin MapStruct todavía).
- Validación de repositorio: que sea `github.com/owner/repo`, no ejecuta nada todavía.

Comandos relevantes:
- `docker compose up -d postgres` (desde el compose inicial de F1).
- `mvn spring-boot:run --spring-boot.run.profiles=api` → Flyway crea la tabla en arranque.
- Probar con curl / REST Client / Insomnia.

**Resultado verificable**
- `POST /api/v1/analyses` con una URL válida crea una fila; `GET` la devuelve; la lista funciona.
- Desde cero: `DROP SCHEMA public CASCADE` en Postgres → reiniciar API → Flyway recrea la tabla.
- Frontend de F2 conectado a datos reales: el badge muestra `queued` persistido.

**Criterios de aceptación**
- Migraciones reproducibles desde un esquema vacío.
- URL inválida → `400` con mensaje claro; URL inexistente en GitHub se validará solo en F4 (el pipeline).
- Records, Optional y streams tipados; `mvn compile` sin errores.
- El enum de estados incluye `analyzingDeps` y `buildingArchitecture` (los usa F4+).

**Riesgos**
- Trampas de JPA (lazy loading, N+1, sesiones fuera de contexto). Antídoto: `@Transactional(readOnly = true)` en lecturas y consultar con `Pageable` o `List<Analysis>` sin relaciones en el MVP.
- Flyway no "autogenera" migraciones como Alembic — las migraciones son SQL manuales. Más control, menos magia; revisar SQL antes de aplicar.
- Spring Boot 4.x usa el namespace `jakarta.*` (no `javax.*`). Los tutoriales viejos usan `javax` — confusión potencial. Buscar siempre "Spring Boot 4" o "Jakarta EE".
- UUID como ID: decidir si se guarda como `UUID` nativo en Postgres (recomendado) o como string.

**Qué NO hacer todavía**
- Redis, colas, worker, análisis real, AI, auth.
- Normalizar tablas de artefactos (se decidió JSONB).
- Endpoints de "admin" ni borrar análisis.

---

### FASE 4 — Análisis determinista #1: clone + scan + JavaParser

**Objetivo**
Primer análisis real: el endpoint `POST /analyses` ejecuta un pipeline sincrónico que clona el repo (tarball), escanea archivos, analiza código Java con JavaParser, y persiste el artefacto JSONB en Postgres.

**Motivación**
El corazón del producto es el análisis de código. Esta fase construye la columna vertebral determinista: clonar, escanear, parsear. El análisis corre **sincrónicamente** en el handler de la petición (la API bloquea durante el análisis). Esto es aceptable para repos pequeños en desarrollo; en F7 se moverá a un worker asíncrono.

**Nuevos conocimientos**
- Clonado **sin git**: descargar `tarball` de `codeload.github.com` y extraer a un directorio temporal con `java.nio.file.Files` + un descompresor GZIP/TAR (Apache Commons Compress o librería equivalente).
- **Guardrails de recursos**: límite de tamaño de repo, nº total de archivos, tamaño por archivo, timeout total. Rechazos con error claro y estado `FAILED`.
- Seguridad: extracción de tar de forma segura (evitar path traversal), uso de `Files.createTempDirectory`, limpieza en `finally`.
- Escaneo: walk del árbol con `Files.walk()`, filtrado por extensión de interés (`.java, .ts, .tsx, .js, .jsx, .json, .toml, .gradle, .md…`), exclusión de `node_modules`, `dist`, `.git`, `.venv`, archivos minificados. Conteos por carpeta y LOC por archivo.
- **JavaParser** (`javaparser-symbol-solver-core`): parsear archivos `.java` a AST, extraer paquetes, clases, interfaces, imports, métodos, dependencias entre paquetes.
- Estructura del artefacto JSON: `modules`, `nodes`, `edges`, `statistics`, `languages`, `meta`.
- Consumo de HTTP en Spring con **RestClient** (reemplaza httpx): `RestClient`, `RestClient.Builder`, manejo de errores.

**Tecnologías**
- `com.github.javaparser:javaparser-symbol-solver-core` (3.26.x — soporta Java 1–25).
- Apache Commons Compress (o lib simplificada) para el tarball.
- `RestClient` (Spring) para codeload.
- Jackson (viene con Spring Boot) para construir el artefacto JSON.

**Implementación**
- `CloneService`: descarga tarball de `https://codeload.github.com/{owner}/{name}/tar.gz/{branch}`, extrae a temp dir, valida estructura mínima, aplica límites.
- `ScannerService`: `Files.walk()`, filtrado, métricas (LOC por archivo, archivos por directorio, top archivos, lenguaje por extensión).
- `JavaParserService`: parsea cada archivo `.java`, extrae package, class/interface names, imports, method count, LOC, y dependencias entre paquetes del mismo repo.
- `ArtifactBuilder`: agrega resultados en el artefacto JSON (schema definido en F1/ADR).
- `AnalysisService.analyze()` orquesta: validate → set CLONING → clone → set SCANNING → scan → parse → build artifact → persist JSONB → status COMPLETED. Cualquier excepción → `FAILED` con `error` claro.
- Estados: `QUEUED → CLONING → SCANNING → COMPLETED | FAILED`.
- Limpieza del directorio temporal vía `finally`.

**Resultado verificable**
- Submit de un repo Java real (pequeño, propio o de ejemplo) → la API ejecuta el análisis → en logs se ven `cloning` y `scanning` → Postgres tiene `structure` JSONB con árbol de carpetas, archivos, tamaños, LOC y módulos Java.
- Submit de un repo gigante → `FAILED` con mensaje claro de límite superado en vez de colgar.
- Submit simultáneo de 2-3 repos → se procesan secuencialmente (sincrónico) sin romper.

**Criterios de aceptación**
- Estado final correcto en Postgres para éxito y para cada error probado.
- Los archivos `.java` se parsean con JavaParser: paquetes, clases, imports extraídos correctamente.
- Sin dependencia de ningún servidor externo más allá de codeload + tu repo de prueba.
- `mvn compile` sin errores; logs estructurados con el stage.

**Riesgos**
- Empezar a resolver imports entre módulos antes de tiempo en esta fase. NO: la resolución a nivel módulo se completa en F5.
- El clon tarball falla por rate limit de GitHub → se maneja como fallo transitorio (F9); por ahora basta log claro.
- Spaghetti en el pipeline → mantener funciones limpias y una orquestadora explícita. No introducir ninguna librería de pipelines todavía.
- JavaParser con sintaxis rota en un archivo → catch y continuar, registrar el error, nunca abortar todo el análisis.

**Qué NO hacer todavía**
- tree-sitter, dependencias de manifests, metadata GitHub, grafo de imports entre módulos, AI.
- Redis, colas, worker, reintentos automáticos (F9), rate limiting (F9), SSE.
- Ejecutar ningún código del repositorio analizado.

---

### FASE 5 — Análisis determinista #2: tree-sitter + dependencias

**Objetivo**
Ampliar el pipeline con análisis de TypeScript/JavaScript (tree-sitter), parsing de manifests de dependencias, detección de framework, y metadatos de GitHub. El artefacto JSONB se enriquece con edges, dependencias y frameworks.

**Motivación**
El mapa y la documentación AI (F8) necesitan contexto: ¿qué lenguaje, qué framework, qué paquetes? El onboarding debe poder decir "es una app React+Spring con estas deps". Todo esto se obtiene programáticamente; la AI solo lo explicará.

**Nuevos conocimientos**
- **tree-sitter (bonede JVM bindings)**: `io.github.bonede:tree-sitter` (0.26.x) + gramáticas de lenguaje (`tree-sitter-typescript`, `tree-sitter-java`). Parsear TS/TSX/JS/JSX a CST, extraer imports, clases, funciones. Conocer qué es un CST y por qué es mejor que regex.
- Resolución de imports a **nivel módulo**: definición de "módulo" por lenguaje:
  - Java: paquete/`package`.
  - TS/JS: directorio de nivel superior bajo la raíz de código (secundariamente archivos raíz). Cada archivo pertenece a un módulo; los imports relativos se resuelven a su módulo.
- Agregación: nodo = módulo; edge = relación import→import destino con **count** de frecuencia. External (bare packages sin resolución local) se agrupan en un nodo `external` único o se omiten del grafo pero se listan en stats.
- Consumo de la **GitHub REST API** con `RestClient`: metadatos de repo (descripción, default branch, lenguaje, stars), manejo del **rate limit** (60 req/h anónimo; 5000 req/h con token opcional vía `GITHUB_TOKEN`).
- Captura del **commit SHA** (usado en F8 para caching de AI).
- Parsing de manifests **sin regex ad-hoc**:
  - `package.json` → Jackson (ya en el stack).
  - `pom.xml` → Jackson (dataformat XML) o JAXB.
  - `build.gradle` → parseo básico de texto (scope mínimo).
- Heurística de framework a partir de dependencias: tablas de coincidencia (React/Next/Express/Fastify · Spring Boot/Django/FastAPI · ASP.NET Core/MVC/EF).
- Alias (`tsconfig paths`) y workspaces: **fuera de scope** con estadística "unresolved". Decidir en fase: recomiendo fuera, registrando `unresolved_imports` como métrica.
- Pruebas unitarias deterministas de parsers con **fixtures** (repo de ejemplo en `src/test/resources/fixtures`).

**Tecnologías**
- `io.github.bonede:tree-sitter` (0.26.x) + gramáticas de lenguaje.
- `RestClient` (Spring) para GitHub API (ya en el stack).
- Jackson `jackson-dataformat-xml` (pom.xml) si se prefiere; o `<package> document` con JAXP.
- JUnit 5 (viene con `spring-boot-starter-test`) para tests unitarios de parsers.

**Implementación**
- `TreeSitterService`: parsea archivos `.ts/.tsx/.js/.jsx`, extrae imports, clases, funciones, símbolos.
- `DependenciesService`: parsing de `package.json`, `pom.xml`, `build.gradle` → lista de dependencias `{name, version, kind, file}`.
- `FrameworkDetector`: tabla de heurísticas → detecta frameworks (Spring Boot, Next.js, React, Express, etc.).
- `GitHubService`: metadatos del repo (descripción, lenguaje, default branch, commit SHA, stars) vía REST API. Token opcional `GITHUB_TOKEN`; funcionar también sin él.
- `ModuleResolver`: resuelve imports a módulo (relativos + paquetes locales), `unresolved` como métrica.
- `GraphAggregator`: genera nodos/edges con counts y capas (api, domain, storage, ui, shared).
- Extender `ArtifactBuilder` con modules, nodes, edges, dependencies, frameworks, meta.
- Etapa `ANALYZING_DEPS` + `BUILDING_ARCHITECTURE`.
- Persistir `analysis.modules`, `analysis.edges`, `analysis.dependencies` (JSONB).
- Tests unitarios con fixtures para cada parser y formato de manifest.

**Resultado verificable**
- Análisis de 3 repos de prueba (Java, TypeScript, mixto) muestra: metadatos correctos, deps detectadas, framework detectado, edges entre módulos.
- Repos sin manifests completan la etapa con dependencias vacías sin fallar.
- Tests de fixtures pasan: import relativo (a módulo), import de paquete (→ external), ciclo de imports (no se rompe), archivo sin imports.

**Criterios de aceptación**
- Los parsers pasan tests unitarios con fixtures, incluyendo casos límite (JSON malformado, pom.xml vacío, package.json sin deps).
- El fallo del rate limit de GitHub degrada a "sin metadata" con log claro, **no** hace fallar el análisis.
- Sin `GITHUB_TOKEN` el flujo sigue funcionando (límite más bajo).
- Repos enormes no explotan: cap de nº de archivos analizados (config), los que pasan el cap se excluyen con métrica.
- `unresolved_imports` <= umbral razonable en los repos de prueba, o está documentado por qué no.

**Riesgos** (el más peligroso del proyecto)
- **Resolver imports "a lo perfecto"** (aliases de tsconfig, monorepos con workspaces, imports de barril, path `@/…`). Esto puede consumir semanas. El plan lo corta explícitamente: se resuelven relativos y paquetes locales; el resto → `unresolved` como métrica. **La "perfección" no aporta valor de producto en el MVP.**
- tree-sitter en Windows: verificar que los nativos (DLLs) vienen incluidos en el JAR de bonede. Si no, fallback: usar JavaParser solo (Java) y un parser de imports por regex simple para TS como solución documentada.
- Grafo por archivo con miles de nodos → se agrega a módulo (top-level). Cap global de nodos.
- Gramáticas TS complejas (JSX, decorators) → scope: imports y declaraciones básicas.

**Qué NO hacer todavía**
- LSP/IntelliSense, dependencias transitivas, resolución de tipos, análisis de imports del bundler, renders server, pruebas ejecutando el código, aliases/workspaces.
- Consultas a npm/PyPI APIs, resolución de versiones, licencias, vulnerabilidades.

---

### FASE 6 — Visualización interactiva con React Flow ⭐

**Objetivo**
Mostrar el grafo de módulos como mapa interactivo: nodos, aristas, capas con color, click para detalle, minimapa y controles. Primera pantalla de valor real del producto.

**Motivación**
El producto es "ver la arquitectura". Sin mapa interactivo no hay producto. Además fuerza a definir el contrato de API de arquitectura (`GET /analyses/{id}/architecture`) que la AI (F8) reutilizará.

**Nuevos conocimientos**
- **@xyflow/react (React Flow v12)**: nodos/edges, custom nodes, `handle`, state de selección, `MiniMap`, `Controls`, SSR-safe rendering.
- Layout de grafos con **dagre** (simple, jerárquico) sobre el grafo dirigido módulo→módulo; entender limitaciones con grafos cíclicos.
- Mapeo datos (JSONB) → elementos de React Flow; normalización client-side.
- UX de grafos: legend, color por capa, tamaño de nodo ∝ LOC, filtrado de nodos "external", pan/zoom, fit view.

**Tecnologías**
- `@xyflow/react@^12`, `dagre` (layout). Alternativa más potente `elkjs` — se recomienda dagre por simplicidad; elkjs queda como mejora post-MVP.

**Implementación**
- `GET /api/v1/analyses/{id}/architecture` en Spring Boot (devuelve modules + edges + structure + deps resumidos).
- Componentes en `apps/web`:
  - `AnalysisMap` (área React Flow).
  - `ModuleSidebar` (panel al click: path, LOC, archivos del módulo, deps entrantes/salientes).
  - `MapHeader` (repo, framework, capas, legend, "volver").
- Lógica de layout en `lib/map.ts` (JSONB → React Flow).
- Límites: si > 40 nodos, agrupar/colapsar capas con count (suficiente para MVP); global cap.
- Integración: el detalle del análisis (`/analyses/[id]`) muestra el mapa cuando está `COMPLETED`; placeholder mientras tanto.

**Resultado verificable**
- Con un repositorio analizado en F4+F5, el mapa muestra los módulos correctos, aristas de dependencia con dirección, capas coloreadas, y click en un nodo abre detalles desde la API.

**Criterios de aceptación**
- El mapa se renderiza desde datos reales (no mock) de repos Java y TypeScript sin romper.
- Navegación: pan, zoom, minimapa, "ajustar a vista", reset. Sin JS crippled states (SSR-safe).
- Repo con ciclo de imports no provoca layout roto (dagre maneja ciclos, verificarlo).
- Panel lateral conectado de verdad al nodo seleccionado.

**Riesgos**
- Personalizar nodos/aspecto más de lo necesario → prefabricar look simple y pulirlo en F13.
- Layout problemático en grafos densos → cap de nodos y colapso por capa (ya decidido).
- React Flow + Next SSR: usar el patrón de montar en cliente (dynamic import `ssr: false`) — error típico.

**Qué NO hacer todavía**
- Edición de grafos, drag-to-rearrange persistido, agrupaciones avanzadas, file-level view.

---

### FASE 7 — Pipeline asíncrono: Redis Streams + worker Spring Boot ⭐

**Objetivo**
Que `POST /analyses` encargue un job **real a un worker asíncrono** que clona el repo y ejecuta todo el pipeline de análisis (F4+F5), mientras la API actualiza el estado en Postgres. Primer flujo asíncrono completo del producto.

**Motivación (arquitectónica)**
```text
El análisis tarda segundos/minutos
        ↓
no debe bloquear la petición HTTP
        ↓
necesitamos un background job
        ↓
necesitamos una cola (Redis Streams)
        ↓
necesitamos un worker (Spring Boot, segundo proceso)
```
Además: **Redis no corre en Windows** → en desarrollo el worker, Redis y Postgres viven en Docker. Esta es la primera necesidad real de Docker para el worker y será la base del **stack reproducible final** (F11/F12).

**Nuevos conocimientos**
- **Redis Streams**: `XADD` para encolar, `XREADGROUP` con consumer groups para consumir, `XACK` para confirmar, `XAUTOCLAIM` para recuperar jobs colgados (pendientes en la PEL). Conceptos: stream, consumer group, consumer, pending entries list (PEL), dead-letter.
- **Spring Data Redis `StreamMessageListenerContainer`**: `receive()` con `StreamReceiver` y `ReceiverOptions` (desde el primer id o por grupo), `@Profile("worker")` para el componente consumidor.
- **Dos entrypoints del mismo proyecto Spring Boot**: `--spring.profiles.active=api` (solo capa web) y `--spring.profiles.active=worker` (solo stream consumer). En Docker: dos contenedores con la misma imagen, diferentes `command`.
- **Docker Compose 5 servicios**: web, api, worker, redis, postgres + volúmenes + healthchecks.
- Worker lifecycle: `StreamListener`/`StreamMessageListenerContainer` iniciado con `ApplicationReadyEvent`; graceful shutdown.
- **Clasificador de excepciones**: transitorio (timeout, 5xx, 429) vs permanente (repo no existe, límite de tamaño, tar corrupta). Retry solo transitorios.

**Tecnologías**
- `spring-boot-starter-data-redis` (incluye `StreamMessageListenerContainer`).
- Lettuce es el driver por defecto (viene con el starter).
- Redis 7.x (Docker; 7+ para `XAUTOCLAIM` con lista de IDs eliminados).
- `RestClient` (Spring) para clonar tarballs (ya en F4).

**Implementación**
- `RedisStreamConfig`: configura la conexión Redis, stream `analysis:jobs`, consumer group, `StreamMessageListenerContainer`.
- `StreamProducer` (en perfil `api`): `XADD` al crear análisis → encola `{analysisId, owner, name, repoUrl, commit}`.
- `StreamConsumer` (en perfil `worker`): consume del stream, ejecuta el pipeline de análisis (F4+F5), actualiza estado en Postgres, `XACK` al terminar. Excepciones transitorias → no-ack (queda en PEL para recuperación); permanentes → `FAILED` + ack.
- `POST /api/v1/analyses` ahora escribe al stream y devuelve `202` (ya no bloquea).
- Estados visibles en la UI: `QUEUED → CLONING → SCANNING → ANALYZING_DEPS → BUILDING_ARCHITECTURE → COMPLETED | FAILED`.
- `compose.yml` con 5 servicios: `web`, `api`, `worker` (misma imagen, diferente command), `redis`, `postgres`.

Comandos relevantes:
- `docker compose up --build` (todos los servicios).
- `docker compose logs -f worker` (ver etapas en vivo).
- Lanzar análisis desde Swagger UI / frontend y observar el pipeline asíncrono.

**Resultado verificable**
- Submit de un repo real → la API responde `202` inmediatamente → el worker procesa el job → la UI muestra el progreso por polling → Postgres tiene el artefacto completo.
- Submit de un repo gigante → `FAILED` con mensaje claro de límite superado en vez de colgar.
- Matar el worker a mitad del análisis → el job queda en la PEL; al reiniciar el worker, lo recupera (XAUTOCLAIM) o se marca `FAILED` por timeout.
- Submit simultáneo de 2-3 repos → se procesan en cola sin romper.

**Criterios de aceptación**
- Estado final correcto en Postgres para éxito y para cada error probado.
- El worker arranca solo con el profile `worker`, consume del stream, procesa jobs, y hace `XACK`.
- Matar el worker a mitad de un análisis → la fila queda en estado intermedio consistente (no corrupto); al reiniciar, el job se reintenta.
- La API (profile `api`) no consume jobs y el worker no expone endpoints HTTP.
- Logs estructurados en cada transición de estado.

**Riesgos**
- El worker no arranca porque el stream o el consumer group no existen aún → crearlos en `RedisStreamConfig` al inicializar (idempotente), o `XADD` desde la API garantiza la creación del stream.
- `XAUTOCLAIM` con Redis < 6.2 no soporta el argumento `deletedIds` (añadido en 7.0). Usar Redis 7+ en Docker.
- Spaghetti en el pipeline del worker → reutilizar las mismas funciones del análisis de F4/F5; solo cambiar la orquestación.
- El `StreamMessageListenerContainer` puede consumir mensajes antes de que el grupo exista → configurar `createConsumerGroup` al arrancar.

**Qué NO hacer todavía**
- Colas de prioridad, dead-letter stream explícita (se añade en F9), RabbitMQ, autoescalado de workers, monitores GUI. Ejecutar código del repositorio analizado.

---

### FASE 8 — AI enrichment: onboarding + explicaciones (best-effort) ⭐

**Objetivo**
Usar la AI como **capa de documentación** sobre los datos deterministas: explicación por módulo, documento de onboarding, y "por dónde empezar a mirar". Con coste controlado y degradación elegante.

**Motivación**
Regla de oro del proyecto:

```text
Análisis determinista → descubre hechos
AI                   → interpreta, resume, explica
```

La AI **nunca descubre hechos** (no "lee el repo"). Recibe subconjuntos curados: estructura, módulos, edges, deps, framework, entry points, README truncado. Esto controla tokens/coste y mantiene el análisis honesto.

**Nuevos conocimientos**
- **OpenAI Java SDK** (`com.openai:openai-java`) o **RestClient** thin wrapper contra la API de OpenAI. Recomendación para MVP: thin client propio con `RestClient` + Jackson (menos dependencias, aprendes el contrato), dejando el SDK oficial como alternativa si la integración se complica.
- Diseño de prompts por tarea con **structured outputs** (JSON schema) para explicaciones por módulo y lista "start here"; render en markdown para onboarding.
- **Selección de contexto**: qué enviar y qué no. README truncado, entry points (main/App/Program), configs, summaries de módulos, top N por LOC/degree. Nunca el repo entero.
- **Presupuesto de tokens/coste**: cap de llamadas por análisis, cap de tokens por llamada, modelo económico (ej. `gpt-4o-mini`), suma de coste por análisis persistida.
- **Caching de AI**: mismo `commit` + `modelo` + `versión de esquema` → no re-llamar (enlace con el dedupe de F9).
- **Degradación**: `aiStatus` = PENDING/RUNNING/COMPLETED/DEGRADED/FAILED. Si la AI falla, el producto sigue con mapa + deps; la UI lo dice. Nunca bloquear el análisis determinista por AI.
- Retry de la AI con backoff (429/5xx sí; 400 no) reutilizando el clasificador de transitorios.

**Tecnologías**
- **OpenAI Java SDK** o Spring `RestClient` + Jackson. Base URL configurable por env (permite proveedores OpenAI-compatibles más baratos, hack barato y documentable). **Sin LangChain** (añade abstracción, no valor aquí) y **sin embeddings/RAG**: la selección de contexto es determinista, barata, predecible y cacheable — es una decisión consciente a explicar en entrevista (ver "Por qué NO RAG" abajo).

**Implementación**
- `ai/` package:
  - `ContextBuilder` — construcción del contexto curado (budgets).
  - `PromptTemplates` — templates por tarea.
  - `OpenAIClient` — RestClient/SDK, retry, structured output, contador de tokens.
  - `AICacheRepository` — cache por commit en Postgres (tabla `ai_cache` o columna en `analysis`).
- Etapa `GENERATING_ONBOARDING` en el worker; persistir `aiDocs` JSONB + `aiStatus` + `aiUsage` (tokens + coste estimado).
- Llamadas (máx. 3–5 según presupuesto):
  1. Explicaciones por módulo (JSON, batch de módulos en pocas llamadas).
  2. Documento de onboarding en markdown (resumen arquitectónico, módulos clave, convenciones, cómo correr).
  3. "Dónde empezar" (3–5 rutas con motivo).
- `MAX_LLM_CALLS` / `AI_BUDGET_USD` configurables en `application.yml`.
- Endpoint `GET /api/v1/analyses/{id}/documents` para la UI (onboarding markdown + start here).

**Resultado verificable**
- En repos de prueba: onboarding markdown razonable, explicaciones por módulo, lista "start here" con rutas reales.
- Doble análisis del mismo commit → segunda vez sin llamadas a la API de AI (cache). Se ve en logs/contador.
- AI con `API key` inválida o rate-limit: análisis completa y `aiStatus=DEGRADED`/`FAILED`, la UI lo muestra sin romper el mapa.

**Criterios de aceptación**
- El coste de un análisis queda bajo control (medirlo y mostrarlo; objetivo < ~$0,05 por análisis típico).
- structured outputs validados con el schema; fallos de parseo → reintento o degradar a `failed`.
- Documentado en README/ADR: qué decide la AI y qué no.
- Sin AI key, el sistema entero sigue funcionando (determinista 100%).

**Riesgos**
- **Rabbit hole de prompt tuning** → límite de tiempo explícito en la fase; "suficientemente bueno" = onboarding correcto y neutral, no perfecto.
- Enviar contexto enorme (mejores resultados percibidos pero coste disparado) → budgets duros por env.
- Depender de la AI para el encabezado del mapa → no: el modelo determinista manda.

**Por qué NO RAG/embeddings aquí (decisión documentable):**
El contexto relevante de un repo (estructura, módulos, deps, entry points) es **estructural**, no semántico. La selección programática + presupuestos hace el trabajo de forma determinista, barata y cacheable. RAG sería complejidad y coste sin ganancia en el MVP. Si algún día se quisiera "descubrir" qué archivos son importantes por similitud, sería una extensión post-MVP, no parte del núcleo.

**Qué NO hacer todavía**
- Agentes/gran llamada única, UI chatbot, fine-tuning, multi-modelo, LangChain/LlamaIndex, embeddings/RAG.

---

### FASE 9 — Robustez: retries, idempotencia, rate limiting

**Objetivo**
Hacer el sistema asíncrono fiable: reintentos solo para fallos transitorios, sin duplicar trabajo, y protegiendo la API contra abuso. Dead-letter stream para fallos permanentes.

**Motivación**
Aquí es donde aprendes de verdad "background jobs hechos bien". Un sistema así sin esto se rompe en cualquier sistema real en la primera (a) caída de red al clonar, (b) tasa 429 de GitHub/AI, (c) doble clic del usuario o (d) spam. Es el corazón de tu historial de entrevista.

**Nuevos conocimientos**
- **Política de reintentos**: clasificar fallos en transitorios (timeout red, 5xx, 429) vs permanentes (repo no existe, límite de tamaño, tar corrupta). Retry solo transitorios, máximo 2–3, con backoff.
- **Recuperación con `XAUTOCLAIM`**: reclamar entradas pendientes de la PEL que llevan más de N segundos procesándose (job colgado tras crash) → timeout por job.
- **Dead-letter stream** `analysis:dead` para fallos permanentes (o intentos agotados): se ack del principal y se registra con motivo. Se podrá re-inspeccionar manualmente.
- **Idempotencia**: si el mismo `owner/repo` ya tiene un análisis reciente (mismo commit), devolver ese en vez de encolar otro. Control de carrera entre POSTs simultáneos vía verificación en transacción o restricción. Clave: el "dedupe" por commit SHA será la base del cache de AI (F8).
- **Rate limiting** de la API implementado a mano sobre Redis (sliding window / token bucket): protege `POST /analyses`. Aprender el algoritmo es el objetivo, no instalar un middleware.
- Timeout y "max time per analysis" configurable; estados consistentes en cada transición.

**Tecnologías**
- `spring-boot-starter-data-redis` (ya en el stack) → `StringRedisTemplate`/`RedisTemplate` para contadores, `RedisAtomicLong`/`RedisBitCommands` según algoritmo.
- Retry/backoff: implementación propia o Spring `@Retryable` (spring-retry). Recomendación: implementación explícita corta (clasificador + máximo de intentos + backoff), más didáctica y sin dependencia extra.
- Logback (ya presente) para logs estructurados en cada transición.

**Implementación**
- `FailureClassifier` (transitorio/permanente) en el paquete `core`.
- `RetryPolicy` con backoff en clon/descarga y en llamadas AI (preparando integración con F8).
- Recuperación programada: `@Scheduled` (worker) que ejecuta `XAUTOCLAIM` sobre `analysis:jobs` cada N segundos; timeout de procesamiento por job.
- Dead-letter: al agotar reintentos o ante fallo permanente, mover a `analysis:dead` con motivo y ack del principal.
- Dedupe por `(owner, name, commit)` → devolver análisis existente; flag `force` opcional en el body.
- Middleware/interceptor de rate limit en la API (por IP anónima) sobre Redis: sliding window con INCR+EXPIRE o token bucket atómico (Lua o RedisAtomicLong).
- Tests específicos: doble submit, kill worker, 429 simulado → retry, abuso → 429 (revisar en F10).

**Resultado verificable**
- El mismo repo enviado dos veces seguidas produce **un solo** análisis nuevo reutilizado (o el anterior marcado como "reciente").
- Matar el worker durante el clon y relanzar el job → reintento limpio o `FAILED` con error claro, nunca estado corrupto. Un job que se cuelga (sin crash) se re-claima tras el timeout (XAUTOCLAIM).
- Más de N peticiones en la ventana de tiempo → `429 Too Many Requests` con `Retry-After`.
- Fallos permanentes aparecen en `analysis:dead` con motivo.

**Criterios de aceptación**
- Testable de forma determinista: se simulan fallos (mock de HTTP/OpenAI) y se verifica el número exacto de reintentos.
- Los jobs solo guardan estado final `COMPLETED` o `FAILED`; ningún estado intermedio queda "colgado" tras timeout.
- El rate limit usa Redis de verdad (probar que funciona sin API).
- Logs estructurados en cada transición (job id, analysis id, motivo).

**Riesgos**
- Reintentos infinitos → máx. 3 y configurable por env.
- El dedupe por commit falla si se re-analiza siempre el mismo commit (caso "quiero forzar análisis nuevo") → flag `force` opcional en el body, o ventana temporal (24h) configurable.
- Implementar el rate limit sobre-ingenierizado (fixed window con Lua desde el inicio) → empezar con sliding window simple sobre INCR + EXPIRE.
- XAUTOCLAIM y PEL: vigilar que un job no se procese dos veces a la vez (doble consumidor) → marcar `startedAt` en Postgres y comparar con timeout antes de procesar.

**Qué NO hacer todavía**
- Colas de prioridad, RabbitMQ/Kafka, autoescalado de workers, cluster de Redis, monitores GUI tipo dashboard (logs estructurados bastan).

---

### FASE 10 — Testing y observabilidad

**Objetivo**
Blindar lo construido con pruebas reales y añadir observabilidad (logs estructurados, métricas, health) y CI verde en GitHub Actions.

**Motivación**
A estas alturas ya hay lógica de parsing, pipeline asíncrono, retries y AI. Sin tests, cualquier cambio posterior rompe cosas en silencio. Y un sistema asíncrono sin trazas es un agujero negro cuando algo falla. Es la diferencia entre "hice un proyecto" y "construí un sistema".

**Nuevos conocimientos**
- **JUnit 5 + AssertJ + Mockito**: base completa con `spring-boot-starter-test`.
- **Testing de capas de Spring**:
  - `@WebMvcTest` (controllers), `@DataJpaTest` (repositorios), `@SpringBootTest` (integración).
- **Testcontainers**: PostgreSQL y Redis reales en contenedor para tests de integración (`@ServiceConnection` de Spring Boot 4 simplifica la conexión). Prefiere `@DynamicPropertySource` si usas un contenedor custom.
- Determinismo en jobs asíncronos: Redis real en Docker para tests de integración, fixtures locales de repos, mocks de HTTP/OpenAI (`MockRestServiceServer` de Spring o `@MockBean`/`WireMock`).
- Flujos de retry: verificar número de reintentos y backoff sin sleeps reales (control de reloj o inyección de scheduler).
- **Observabilidad**:
  - `spring-boot-starter-actuator`: `/actuator/health`, `/actuator/metrics`, `/actuator/info`, readiness/liveness.
  - **Micrometer** para métricas: histograma de latencias API, duración de jobs por etapa, contador de jobs por estado, coste AI acumulado.
  - **Logback JSON layout** (logstash-logback-encoder) para logs estructurados; incluir `analysisId`, `jobId`, `stage` por evento. Redactar secretos.
- CI con GitHub Actions: jobs de build Maven (`mvn verify`) y frontend (`npm ci && npm run build && npm run test`).

**Tecnologías**
- Backend: `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito, `MockMvc`), `org.testcontainers:postgresql`, `org.testcontainers:redis`.
- Obs: `spring-boot-starter-actuator`, micrometer-registry-prometheus (opcional endpoint `/actuator/prometheus`), logstash-logback-encoder.
- Frontend: `vitest` + `@testing-library/react` (pocos tests: form, stages, mapa render con datos fake).
- CI: GitHub Actions (jobs: `mvn verify`, `npm run build` + tests, typecheck).

**Implementación**
- `src/test/java/...` en backend: unit (parsers, agregación, rate limit, clasificador de errores), servicios (con mocks), integración (pipeline completo contra Postgres+Redis Testcontainers, repo fake local), API (`MockMvc`).
- Mocks: `MockRestServiceServer` para codeload/GitHub; OpenAI → stub de HTTP o `@MockBean`.
- `apps/web` tests: 2-3 componentes clave.
- Workflow CI con matrix de JDK 21 y Node.
- Actuator expuesto (health, info, metrics); endpoint `/actuator/prometheus` opcional.
- Logback JSON: layout estructurado en `application.yml` o `logback-spring.xml`.
- Tests de regresión: cada bug corregido en F4-F9 debe recibir un test.

**Resultado verificable**
- `mvn verify` (backend) y `npm run test` (web) verdes en local y en CI tras push.
- Un test de integración lanza el pipeline completo contra servicios reales (Testcontainers) y verifica éxito/fallo determinista.
- `/actuator/health` responde `UP`; logs con campos estructurados (`analysisId`, `stage`); `/actuator/metrics` muestra contadores.

**Criterios de aceptación**
- Cobertura razonable de los paths críticos (parsers, pipeline, retries, rate limit, dedupe) — no 100%, sino los que evitan regresiones.
- CI ejecuta build + tests en cada push.
- Los tests no dependen de red externa (todo mockeado) salvo los de integración explícitamente marcados.
- Documentado en README cómo correr tests y cómo leer los logs/métricas.

**Riesgos**
- Tests flaky (timeouts, puertos en uso, contenedores lentos) → helpers deterministas (esperar readiness, puertos efímeros, fixture de "reset db").
- Sobredimensionar observabilidad (backends, dashboards) → Actuator + logs JSON + métricas clave bastan para el MVP; un backend OTLP/dashboard self-hosted es opción post-MVP.
- Mocks que "mienten" vs la realidad → tests de integración reales (Testcontainers) para el pipeline, mocks solo para HTTP externo (GitHub/OpenAI).

**Qué NO hacer todavía**
- E2E Playwright completo, coverage 100%, dashboards complejos, centralizado de logs, alerting.

---

### FASE 11 — Docker: entorno local reproducible

**Objetivo**
Empaquetar todo en imágenes reproducibles y conseguir que `docker compose up --build` levante **todo** el sistema en local: web, api, worker, redis y postgres con un solo comando.

**Motivación**
Ya se usó Docker como medio de desarrollo (F7+). Ahora se hace bien: imágenes optimizadas, multi-stage, usuario no root, healthchecks, `.dockerignore`. Este compose **ES el entregable de ejecución del proyecto**: el destino final no es servirlo para otros, es que cualquiera pueda arrancar RepoVisor completo con un comando. Es la diferencia entre "funciona en mi máquina" y "funciona en cualquier máquina con Docker".

**Nuevos conocimientos**
- **Multi-stage build Maven**: etapa build con `maven:3.9-eclipse-temurin-21` → `mvn package`; etapa runtime con `eclipse-temurin:21-jre` (mucho más pequeña) y usuario no root. Copiar solo el JAR final.
- Frontend **Next.js `output: 'standalone'`**: build de Node con `npx next build`, copiar `next.config` standalone a una imagen node slim.
- Optimización de capas y cache de BuildKit, `.dockerignore`, tamaño de imagen.
- Compose completo: perfiles dev vs "full", env vars desde `.env`, volumen para postgres/redis, orden de arranque y dependencias entre servicios (wait-for health), `restart: unless-stopped`.
- Healthchecks: ejecutables dentro del contenedor (wget/curl para API, `pg_isready` para Postgres, `redis-cli ping` para Redis), `CMD-SHELL`.

**Tecnologías**
- Docker + Compose v2 (BuildKit).
- Imágenes: `maven:3.9-eclipse-temurin-21` (build), `eclipse-temurin:21-jre` (runtime backend), `node:24-alpine` (runtime web), `postgres`, `redis:7`.

**Implementación**
- `Dockerfile.backend` (multi-stage, un solo JAR = dos entrypoints por profile) y `Dockerfile.web` (Next standalone).
- `compose.yml` con los **5 servicios**: web, api, worker, redis, postgres + volúmenes + healthchecks.
- Perfiles o variable `TARGET=dev|full` para alternar el modo "código montado con reload" vs "imágenes construidas".
- Etiquetado por commit git (`BUILD_SHA`) para trazabilidad.
- `.dockerignore` en backend y web.

**Resultado verificable**
- En una terminal: `docker compose up --build` → healthchecks verdes → `http://localhost:3000` funciona de punta a punta (form → análisis → mapa → onboarding).
- La API responde en `http://localhost:8000`; los logs del worker son visibles con `docker compose logs -f worker`.
- Builds reproducibles (la misma imagen sale igual en dos máquinas).

**Criterios de aceptación**
- Imágenes sin secretos incrustados (todo por env), sin usuario root en runtime.
- Healthchecks marcan ready/healthy en los 5 servicios.
- El worker arranca automáticamente y procesa jobs tras un reinicio del contenedor.
- Tamaños razonables (guías: imagen JRE backend < ~400MB, Next standalone < ~200MB; comprobar).
- Se puede resetear el estado por completo con `docker compose down -v` y volver a arrancar sin pasos extra.

**Riesgos**
- Problemas de build tree-sitter en la imagen slim → fijar la dependencia como JAR (los nativos de bonede vienen incluidos) o pasar a imagen con build-deps solo en la etapa de build.
- Enredarse con multi-stage → es simple aquí: una etapa Maven (build) y una runtime; Next un solo stage standalone.
- Uso de imágenes enormes por descuido → revisar `docker image ls` y `.dockerignore`.

**Qué NO hacer todavía** (esto ya es definitivo, no "de momento")
- Kubernetes, registro de imágenes remoto, builds remotas, orquestadores, Helm. Nada orientado a servir tráfico externo.

---

### FASE 12 — Reproducibilidad: bootstrap limpio, transport local y smoke

**Objetivo**
Garantizar que RepoVisor se pueda arrancar en **cualquier máquina con Docker en menos de 5 minutos**: clonar, leer el README, un comando, y el sistema entero funcionando. Incluye un flujo de smoke verificado y determinista, y un transporte **local** para análisis sin red ni servicios externos.

**Motivación**
El entregable final del proyecto es **local**. El equivalente a "desplegar" es aquí **reproducir sin fricción**: si un evaluador o tu futuro yo no pueden arrancar el proyecto en una máquina limpia con un comando, el portfolio falla en el primer contacto. Además, el demo grabado (F13) se apoya en este arranque: si no es reproducible, no es grabable varias veces.

**Nuevos conocimientos**
- Bootstrap limpio: `docker compose up --build` determinista, espera de healthchecks, orden de arranque.
- Gestión de configuración por env: `.env.example`, variables obligatorias vs opcionales validadas en `application.yml` (`OPENAI_API_KEY`, `GITHUB_TOKEN`, límites/budgets), y `.env` local nunca versionado.
- **Transporte "local" para el analizador**: además del clon vía GitHub tarball, el pipeline acepta una **carpeta local** (o un ZIP subido por la UI) como fuente de análisis. Extensión pequeña de F4/F7 con enorme valor de determinismo: tests, smoke y demo offline sin GitHub ni AI.
- Script de smoke E2E local: arranca compose, espera servicios, lanza un análisis (modo local, sin AI o con AI según env), verifica el estado final y los artefactos.
- Documentación de operación: qué hace cada servicio, puertos, cómo ver logs, cómo resetear datos, solución de problemas comunes.

**Tecnologías**
- Docker Compose (ya presente), PowerShell (`scripts/smoke.ps1`) y, si es trivial, un `smoke.sh` equivalente. **No se añaden tecnologías nuevas**: esto es scripting y configuración sobre lo ya construido.

**Implementación**
- `apps/backend/.env.example` + validación de propiedades requeridas en `application.yml` (obligatorias: `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_URL`; opcionales: `OPENAI_API_KEY`, `GITHUB_TOKEN`, límites/budgets).
- Extensión del transporte (F4) con modo `local: /ruta/carpeta` (o endpoint de upload ZIP) para análisis sin red.
- `scripts/smoke.ps1`:
  1. `docker compose up --build` y esperar healthchecks (timeout configurable);
  2. lanza un análisis sobre un repo fixture local (offline, sin AI) y verifica `COMPLETED` + artefactos;
  3. si `OPENAI_API_KEY` presente, lanza un análisis real de un repo GitHub pequeño y verifica `aiStatus=completed` o `degraded`;
  4. imprime un resumen claro (PASS/FAIL por paso).
- README de operación: tabla de servicios, puertos, comandos, reset (`docker compose down -v`), y troubleshooting.
- (Opcional) Job de CI que construye las imágenes y ejecuta el paso offline del smoke para probar la reproducibilidad en máquina limpia.

**Resultado verificable**
- En una máquina limpia: `git clone …` → `cp .env.example .env` (o ni eso, si los defaults bastan) → `docker compose up --build` → web en `:3000`, api en `:8000`.
- `scripts/smoke.ps1` pasa de punta a punta sin red (transporte local) y con una validación real de AI si hay key.

**Criterios de aceptación**
- El arranque no exige pasos manuales más allá de copiar `.env.example` y un comando.
- El smoke offline pasa de forma determinista dos veces seguidas (sin red ni timeouts raros).
- Se puede reproducir el flujo completo con un repo real de GitHub (con Internet) siguiendo solo el README.
- El README documenta por qué el proyecto es local y qué aporta cada componente — es la pieza de portfolio que leerá primero un evaluador.

**Riesgos**
- Enredarse con scripts multi-plataforma → mantener PowerShell como primario y `smoke.sh` solo si es trivial; los comandos del README deben ser copiar-pegar reales y validados.
- Confundir "reproducibilidad" con "automatización excesiva" → objetivo explícito: un comando y < 5 minutos; el resto es limpieza, no herramienta.
- El transporte local crea una vía "no real" en el pipeline → mantenerla claramente separada del transporte GitHub (nunca mezclar código), y cubierta por tests en F10.

**Qué NO hacer todavía**
- Deployment público, PaaS, dominio/HTTPS, registros remotos, backups externos. Nada cuyo único objetivo sea servir a otros usuarios.

---

### FASE 13 — Pulido, demo grabada y versión portfolio

**Objetivo**
Convertir el MVP en algo presentable y demostrable: UX final, README fuerte con decisiones, y una **demo grabada en vídeo** del flujo completo de producto.

**Motivación**
El portfolio no es solo el código: es cómo se ve, cómo se explica, cómo se demuestra y qué historia cuenta. Sin deployment, la "exhibición" del proyecto es (1) el repo con un README impecable y reproducible, y (2) un vídeo de demo que recorre el producto de punta a punta. Esta fase es barata comparada con el valor que da a entrevistas.

**Nuevos conocimientos**
- Poca tecnología nueva; mucho criterio: estados vacíos/error/loading, escala de nodos, leyenda, "start here" integrado en el mapa, onboarding en pestaña, listado de análisis previos re-abrible.
- SEO/OG local (metadatos + favicon/título) — irrelevante para ranking, útil para presentabilidad del README/capturas.
- **Guion de demo**: cómo estructurar un vídeo corto (2–3 min) que enseñe progreso asíncrono, mapa y onboarding sin florecer.
- Presentación del proyecto: README con arquitectura, decisiones (ADR compilados), "cómo correr localmente", capturas/GIF, vídeo de demo corto, y una sección de "trade-offs y siguiente pasos" — esto es lo que se lee en 30s de entrevista.

**Implementación**
- (Opcional y recomendado si aporta) **SSE** para progreso en vivo sustituyendo al polling, o mantener polling si el SSE no aporta. Decisión: si ya está todo, implementar SSE es un extra de producto pequeño (ya lo dominas) que mejora la percepción del vídeo de demo. Solo si no retrasa.
- Página de análisis por UUID (sin auth; suficiente para el MVP y para el vídeo).
- Pulido del mapa (colores, sizes, leyenda, tooltips) y de la página del onboarding (markdown bonito).
- **Demo grabada**: vídeo corto (2–3 min) siguiendo el flujo:

  ```text
  GitHub URL → Job creado → Redis Stream → Worker → Análisis determinista → AI enrichment
    → Resultado → Mapa interactivo → Onboarding
  ```

  Con un repo de ejemplo escogido (pequeño, con varios módulos, en un lenguaje que domines). Opción B (fallback sin Internet): demo del mismo flujo usando el **transporte local** de F12.
- README final + capturas/GIF + enlace al vídeo.
- Checklist de "qué decir en entrevista" (juicios de diseño hechos aquí).

**Resultado verificable**
- Una persona ajena clona el repo, sigue el README, arranca con **un comando** y analiza un repo entendiendo el resultado sin asistencia.
- Existe un vídeo de demo reproducible que recorre el flujo completo del producto.
- README cuenta la arquitectura, decisiones y trade-offs en < 5 min de lectura.

**Criterios de aceptación**
- La demo es un vídeo con guion y repo de ejemplo fijos; puede recrearse en cualquier momento, no depende de un servicio externo que pueda estar caído.
- Los estados de error del producto completo son legibles ("repo demasiado grande", "AI no disponible").
- Limpieza: sin dead code, sin dependencias sin uso, logs estructurados como se documentó.
- (Opcional) SSE live funcionando en el stack local.

**Riesgos**
- **Polish forever** → tope de tiempo en la fase; "suficientemente bueno" revisado contra la checklist.
- Añadir features de golpe (auth, share links con usuario, etc.) → se listan en "Versión portfolio" (E/F) y se posponen.

**Qué NO hacer todavía**
- Auth real/completa, multi-tenant, pagos, billing, feature flags, i18n, app mobile.

---

# C. Dependencias entre fases

```text
F1 ──► F3 ──► F4 ──► F5 ──► F6 ──► F13
 │                                   ▲
 ├─► F2 ─────────────────────────────┘  (frontend consume API desde F3)
 │
 └─► F7 (necesita F4+F5 async) ──► F8 (necesita F5+F6+F7)
 F7 ──► F9 (robustez sobre el pipeline asíncrono)
 F8 ──► F9 (retries de AI)
 F9 ──► F10 (tests de robustez)        F3 ──► F10 (tests API)
 F10 ──► F11 ──► F12 ──► F13
 F6 ──► F10 (tests web)
```

Explicación de las principales:
- **F2 es frontend con datos mock**: depende solo de F1 (Spring Boot hello). Se adapta a la API real en F3.
- **F3 depende de F1**: necesita JDK/Maven/Spring Boot configurado y Postgres.
- **F4 y F5 dependen de F3**: extienden el `AnalysisService` y las columnas JSONB existentes.
- **F6 depende de F5**: el mapa necesita el grafo producido por el análisis completo.
- **F7 depende de F4+F5** (pipeline de análisis) y de F3 (persistencia). Extrae el pipeline a un worker asíncrono. **Recomendado completarlo antes de F8** (la AI corre dentro del worker).
- **F8 depende de F6 y F7** (contexto determinista desde F4-F5 + ejecución asíncrona).
- **F9 depende de F7** (infra de colas) y **antes de F9** se recomienda tener F8 (los retry/backoff de la AI lo usan). Se puede solapar con F6/F7 sin problema.
- **F10** testea todo lo anterior; por eso va tras F9. Puedes ir escribiendo tests por fase desde F4 (recomendado), pero la fase 10 los consolida y añade CI.
- **F11 y F12** requieren el código estable de F3-F10.

---

# D. Timeline (baseline 6 semanas + variante exprés)

Ritmo asumido: trabajo a tiempo parcial pero constante (≈ 3–4 h/día o fines de semana completos). Las fases con ⭐ son las más pesadas. Se incluyen colchones.

| Semana | Fases | Énfasis |
|---|---|---|
| 1 | F1, F2, F3 | Base: JDK/Maven/Spring Boot, frontend mínimo, JPA + Flyway |
| 2 | F4, F5 | Análisis determinista (JavaParser + tree-sitter + deps) |
| 3 | F6 ⭐, F7 ⭐ | Mapa React Flow + pipeline asíncrono Redis Streams |
| 4 | F8, F9 | AI enrichment con presupuestos y degradación + robustez (retries, idempotencia, rate limit) |
| 5 | F10, F11 | Testing/obs (Testcontainers, Actuator) + Docker reproducción |
| 6 | F12, F13 | Reproducibilidad + smoke + demo y pulido |

- **Semanas 1 y 2** tienen margen para asimilar Java/Spring Boot (zonas nuevas).
- **Semanas 3 y 4** son el corazón del producto: no recortar F7 ni F8.
- **Semana 6** es la más agresiva (2 fases). Si se tarda más en semanas 1–5, la semana 6 solo pierde pulido, no producto.

### Variante exprés (4 semanas)
Prioridad dura: F1→F3→F4→F5→F6→F7→F8→F11→F12. Se eliminan/recortan:
- **F9** reducido a lo mínimo (idempotencia simple) — se retoman retries/rate limit después o se documentan como "next steps".
- **F2** se fusiona con F3 (hacer el frontend mínimo directamente contra la API real).
- **F10** solo tests críticos de regresión, sin observabilidad ni CI full.
- **F13** reducido a README + demo básica grabada.
- Riesgo: la robustez y la observabilidad son exactamente el material de entrevista. La variante exprés es solo si el plazo es innegociable.

### Señal de "estoy en plazo"
Al final de cada semana debe existir una demo incremental (un repo de prueba analizado de punta a punta hasta donde la fase llegue). Si una semana se atasca > 2 días en una fase, recortar el alcance de esa fase antes de arrastrar el retraso (ver riesgos de cada fase).

---

# E. MVP — qué entra y qué NO entra

## Entra en el MVP
1. Analizar repositorios **públicos** de GitHub, limitados en tamaño y nº de archivos.
2. Lenguajes: **Java, TypeScript, JavaScript**. Fuera de estos → rechazo claro o análisis de estructura únicamente (decisión: rechazo con mensaje).
3. Pipeline asíncrono visible por etapas: `queued → cloning → scanning → analyzing_deps → building_architecture → generating_onboarding → completed | failed`, con polling desde la UI.
4. Análisis determinista:
   - clonado por tarball + escaneo + stats (LOC, conteos);
   - metadatos GitHub (descripción, lenguaje, default branch, commit SHA, stars);
   - dependencias de manifests y detección de framework;
   - grafo de **módulos** (paquetes Java o directorios top-level TS/JS) con edges y frecuencias.
5. Mapa interactivo en **React Flow**: nodos por módulo, capas con color, click para detalles (path, LOC, deps in/out).
6. **AI (best-effort)**: explicaciones por módulo + documento de onboarding markdown + "dónde empezar". Cacheado por commit, presupuestado por tokens/coste, **degradable**: falla la AI → el mapa y deps siguen visibles.
7. Persistencia de análisis y **re-apertura** de cualquiera pasada (listado).
8. Robustez básica: reintentos de transitorios (2–3 con XAUTOCLAIM), idempotencia por commit, rate limiting en la API, timeouts, dead-letter stream, estados consistentes.
9. Docker Compose: **stack completo reproducible en local** (web, api, worker, redis, postgres) con un solo comando.
10. Transporte **local** de repos (sin red) para tests y demo.
11. Tests de regresión del núcleo + CI (Maven + Node).

## Fuera del MVP
- **Auth/cuentas de usuario** (páginas por UUID sin auth bastan para el uso local y la demo).
- Otros lenguajes (Go, Rust, Ruby…) — solo si sobra tiempo.
- Resolución de imports "a lo perfecto" (workspaces, aliases, bundlers, LSP, symlinks).
- Grafo a nivel de **archivo** (el mapa es por módulo). Drill-down de archivos es post-MVP.
- Ejecutar los tests del repo analizado / CI analysis.
- Métricas de comunidad/historia del repo (bus factor, PRs, commits).
- Chatbot/assistant AI, RAG, embeddings, LangChain.
- SSE (post-MVP si aporta), offline/PWA, i18n.
- Monitores GUI, dashboards de observabilidad completos.

---

# F. Versión portfolio (post-MVP, elegir 2–3 máx.)

El MVP ya es un portfolio creíble. Estas extensiones lo fortalecen; **elegir 2–3 para no morir de éxito**. Ordenadas por ratio valor/esfuerzo y alineación con tu posicionamiento:

1. **Panel de "coste y uso de AI por análisis"** — tokens por llamada, coste estimado, cache hits. Historia perfecta de "Product + AI con juicio de coste". Datos ya existentes de `ai_usage`; solo se exponen en la UI.
2. **Drill-down file-level dentro de un módulo** — expandir nodo y ver archivos e imports entre ellos. Usa datos que ya genera el pipeline (file↔module existente); añade un grafo secundario. Muy barato visualmente, gran impacto de producto.
3. **SSE en vivo** para sustituir el polling. Pequeño, dominio ya dominado, mejora percepción de "tiempo real".
4. **Soporte de monorepos/workspaces** (package.json workspaces, pnpm/nx, más de un proyecto por repo) — añade realismo a repos grandes; toca F5 (la parte más peligrosa). Hacerlo solo con tiempo y si se documenta bien el alcance.
5. **Extensibilidad demostrada**: añadir un 5º lenguaje vía nueva extensión de tree-sitter (Go o Rust) para enseñar que el pipeline es extensible. Un ADR + una gramática + tests.
6. **OAuth GitHub para repos privados** — introduce auth real (permisos, tokens) y permite analizar repos de tu propia cuenta. Más coste: auth + gestión de tokens.
7. **Observabilidad "real"**: conectar Actuator/Micrometer a un backend de trazas y dashboards **self-hosted** (p. ej. Grafana + Prometheus + OTel collector en local). Refuerza la narrativa de systems engineer sin necesidad de servicios externos.

**Recomendación para tu perfil**: (1) panel de coste AI + (2) drill-down + (3) SSE. Esa terna demuestra producto full-stack, AI con criterio económico y UX refinada — exactamente las tres cosas que quieres vender (Software/Full-Stack/Product/AI). Dejar (4) y (6) como "próximos pasos" en el README.

---

# G. Stack final (y por qué)

| Tecnología | Rol | Por qué (y por qué no otra) |
|---|---|---|
| **Next.js + React + Tailwind** | Frontend | Ya lo dominas; da producto real rápido sin inventar. App Router + client para React Flow. |
| **@xyflow/react (React Flow v12)** | Mapa | Única librería de grafos con una necesidad real (mapa interactivo de arquitectura). Activa y con SSR-safe. |
| **Java 21 LTS** | Lenguaje backend | LTS, moderno (records, sealed, pattern matching), base del ecosistema enterprise. El gap de aprendizaje principal. |
| **Spring Boot 4.1.x** | Framework | OSS actual (2026); DI, autoconfiguración, REST, JPA, Actuator, profiles en un solo ecosistema. **3.5.x quedó EOL en junio 2026.** |
| **Maven** | Build | Estándar Java; `pom.xml` simple y didáctico. Gradle es alternativa válida pero más compleja para un primer proyecto. |
| **Spring Data JPA + Flyway** | ORM/migraciones | JPA es EL ORM Java; Flyway es más simple que Liquibase. |
| **`@JdbcTypeCode(SqlTypes.JSON)`** | JSONB | Artefactos del análisis como JSONB en columnas; sin sobre-modelar tablas. |
| **PostgreSQL** | Persistencia | Ya lo conoces; aquí como fuente de verdad de un sistema multi-servicio. |
| **Redis** | Stream + rate limit | Entra por necesidad (F7: colas; F9: rate limit). No hay otra razón en el MVP. |
| **Redis Streams** | Cola de jobs | Soporte nativo en Spring Data Redis (`StreamMessageListenerContainer`, consumer groups, XAUTOCLAIM). Sustituye a RQ/Celery sin infraestructura extra. |
| **JavaParser** | Parsing Java | AST + symbol solver; extracción de paquetes, clases e imports de forma robusta. |
| **tree-sitter (bonede JVM)** | Parsing TS/JS | CST robusto donde regex falla; bindings JVM mantenidos (`io.github.bonede:tree-sitter` 0.26.x). |
| **RestClient** | HTTP | El HTTP client moderno de Spring para codeload y GitHub API. |
| **OpenAI Java SDK / RestClient** | AI enrichment | Llamadas de interpretación con structured outputs, presupuestadas y cacheables. Sin abstracciones extra. |
| **spring-boot-starter-actuator + Micrometer + Logback JSON** | Observabilidad | Health, métricas y logs estructurados desde F10. Sin backends caros en MVP. |
| **JUnit 5 + AssertJ + Mockito + Testcontainers** | Tests | Backend Java estándar; Postgres/Redis reales en contenedor para integración. |
| **vitest + @testing-library/react** | Tests frontend | Mínimo frontend. |
| **Docker + compose** | Entorno reproducible | Necesario desde F7 por Windows; levanta el stack completo (web, api, worker, redis, postgres) con un comando, en local. |
| **GitHub Actions** | CI | Build + tests por push (JDK 21 + Node). |

**Stack deliberadamente 100 % local**: se ejecuta entero en Docker Compose, sin ningún servicio desplegado para terceros. Docker, Redis, Postgres, el worker y Java son parte del aprendizaje y del diseño; no son preparación para una infraestructura de producción ajena.

**Descartadas a propósito** (para poder explicarlo en entrevista): RQ/Celery/*anything Python*, RabbitMQ/Kafka, Kubernetes, LangChain, embeddings/RAG, AWS y cualquier PaaS público. Cada una tiene un "por qué no" escrito arriba o en las fases.

---

# H. Riesgos principales (qué convierte 4–6 semanas en 2–3 meses)

Ordenados por probabilidad × impacto:

1. **Querer resolver imports "perfectamente"** (aliases @, workspaces, monorepos, barril imports, symlinks). Es la trampa #1 de un analizador de código. **Mitigación**: protocolo de resolución fijado en F5 con `unresolved` como métrica; cap de archivos/nodos; rechazar la tentación iterativa.
2. **Rabbit hole de prompt/AI** (buscar el onboarding perfecto, añadir agentes, multi-modelo, RAG). **Mitigación**: presupuesto duro de tokens/llamadas, "suficientemente bueno" definido en F8, y el determinismo como capa infalible. La AI es complementaria, nunca protagonista.
3. **Scope de "mejores feature"** (auth, chat, file-level, monorepos, métricas de comunidad) introduciéndose en el MVP. **Mitigación**: secciones E y F explícitas + checklist de cada fase de "qué NO hacer todavía".
4. **Windows friction** (Redis no nativo, paths, JAVA_HOME, tree-sitter DLLs, PowerShell vs bash). **Mitigación**: desde F7 el worker/Redis corren en Docker Linux; verificar `java -version`/`mvn -version` en F1; documentar comandos PowerShell. Nunca instalar Redis nativo.
5. **Spring Boot 4.x vs tutoriales de 3.x** (namespace `jakarta`, APIs cambiadas). **Mitigación**: usar como referencia las guías oficiales de Spring Boot 4 y documentación de Spring Framework 7; ante dudas, validar con `mvn dependency:tree` y docs oficiales. Si el bloqueo es grave, bajar a 3.5.x es aceptable (EOL).
6. **Tests asíncronos flaky** que consumen horas de depuración. **Mitigación**: Testcontainers estables, repos fixtures locales (sin red), mocks de HTTP/AI, marcar claramente los tests de integración.
7. **Over-engineering del monorepo/tooling** (paquetes compartidos, workspaces npm, build AOT, pipelines CI exóticos) antes de tener product. **Mitigación**: solo apps/web y apps/backend; el CI real llega en F10.
8. **Coste de la AI en desarrollo y demo** (cada análisis "real" de un repo paga tokens). **Mitigación**: modelo barato, cache por commit, `AI_BUDGET_USD`, y transporte local sin AI (F12) para tener tests y demo offline con coste cero.
9. **Sobrediseñar la arquitectura de datos** (normalizar tablas de artefactos, colas de prioridad, microservicios). **Mitigación**: decisión F1 de JSONB + "hazlo simple, normaliza cuando duela".
10. **Perfección del mapa/UX prematura** (layout custom, animaciones) en F6. **Mitigación**: dagre + cap de nodos; diseño se pulsa en F13.
11. **Perder el norte del objetivo de aprendizaje** (montar microservicios o infra para "parecer avanzado"). **Mitigación**: regla de __necesidad real__ aplicada fase a fase en "Motivación".

---

# Apéndice: conceptos que debes conocer al terminar

Para cada bloque, poder explicar con tus palabras (y un ejemplo del proyecto):

- **Async pipeline**: Redis Streams, consumer groups, PEL, XAUTOCLAIM, job lifecycle, ack/fail, timeout, retry/backoff, dead-letter, idempotencia, rate limiting, degradación graceful, estado distribuido (quién es la fuente de verdad y por qué).
- **Java/Spring Boot**: DI/IoC container, constructor injection, autoconfiguration, profiles, Spring Data JPA, Flyway, records Java, `Optional`, streams, Actuator, ConfigData (application.yml), `@ConfigurationProperties`.
- **Análisis de código**: AST vs CST vs regex, JavaParser + symbol solver, tree-sitter, granularidad de módulo, resolución de imports y sus límites, agregación y conteo, capas/heurísticas.
- **AI applied**: structured outputs, presupuesto de contexto/tokens/coste, caching de llamadas por clave (commit+modelo+esquema), degradación sin depender del proveedor, por qué RAG/LangChain no aplican aquí.
- **Operations**: logs estructurados (Logback JSON), Actuator health/metrics, Testcontainers, composición local reproducible de varios servicios con Docker Compose, coste de AI por análisis.
- **Product**: estados vacíos/error/loading, feedback asíncrono, página por UUID (sin auth, local), decisiones de scope documentadas (ADR).

---

*Documento de planificación. Las decisiones marcadas como "recomendación" son discutibles antes de implementar su fase; las marcadas como "decisión" (JSONB, api+worker mismo proyecto, Postgres fuente de verdad, Redis Streams, tarball clone, cap de imports, Spring Boot 4.x) se asumen firmes salvo que aparezca una razón técnica nueva.*