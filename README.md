# RepoVisor

Full-stack app that analyzes public GitHub repositories and generates an architecture map (graph) and developer onboarding docs.

## Stack

- **Frontend**: Next.js (App Router, TypeScript, Tailwind CSS)
- **Backend**: Java 21 + Spring Boot
- **Storage**: PostgreSQL + Redis (Docker Compose)
- **AI**: OpenAI (interpretation layer only — analysis is deterministic)

## Structure

```
apps/
  web/        Next.js frontend
  backend/    Spring Boot API + worker
docs/
  PLAN.md     phase-by-phase development plan
  decisions/  architecture decision records (ADRs)
```

## Dev

Prerequisites: JDK 21, Node.js 24+, Docker (for Postgres/Redis).

```bash
# Backend (from apps/backend)
./mvnw spring-boot:run

# Frontend (from apps/web)
npm install
npm run dev
```
