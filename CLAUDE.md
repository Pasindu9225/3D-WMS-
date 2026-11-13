# 3D Warehouse System

Read docs/SPEC.md before any task. It is the single source of truth; if this file and SPEC.md disagree, SPEC.md wins. Current version: v0.1 (update as you go).

## Stack
Java 17, Spring Boot 4.1, Hibernate, MySQL 8.4 LTS, Flyway, Caffeine, Bucket4j.
React 19 + TypeScript strict + Vite, Tailwind + shadcn/ui, TanStack Query,
Zustand, React Hook Form + Zod, three.js + React Three Fiber 9, react-konva.

## Commands
backend:  ./mvnw verify            (tests + ArchUnit)
backend:  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
frontend: npm run dev | npm test | npm run build | npm run size
local db: docker compose -f deploy/docker-compose.yml up mysql

## Hard rules
- Plan first, code after approval. One task per session.
- Every tenant table has tenant_id and uses @TenantId. Never findById in services;
  use findByPublicIdAndTenant.
- Stock changes ONLY through StockMovementService, inside a transaction, with row locks.
- Request bodies are Java records with Bean Validation. Never bind to entities.
- No native SQL built with strings. No raw tokens or password hashes in responses or logs.
- Every controller method has @PreAuthorize with a permission.
- Tokens only in HttpOnly cookies. Never localStorage or sessionStorage.
- Errors: ProblemDetail with code + correlationId. No stack traces to clients.
- Paginate every list (max 100). Use DTO projections for lists.
- No new heavy dependency or new server without asking. Must run on 4 GB RAM.
- All UI text in frontend/src/lib/i18n/en.json.
- Name the SEC-xx rules you implemented in each PR description.

## Style
Backend: package by module (api/service/domain/repo). Constructor injection.
Frontend: one folder per feature; components small; no any.

## Project docs
- docs/SPEC.md: full spec (versions, tasks, security rules SEC-xx, schema, API)
- docs/security-checklist.md: tick SEC rules as they are implemented
- docs/questions.md: write open questions here instead of guessing
- docs/decisions.md: log important decisions with date and reason
