# Shared contracts

- [`openapi.json`](openapi.json) is captured from the Spring API's `/v3/api-docs`. [`src/api.d.ts`](src/api.d.ts) is generated from it and used by the web API client. Change the Java API first, then regenerate both files.
- [`task-notification.v1.schema.json`](task-notification.v1.schema.json) is the internal RabbitMQ notification contract, separate from HTTP OpenAPI. A backend contract test checks alignment with [`TaskNotification`](../../apps/api/src/main/java/com/vibestory/dispatch/TaskNotification.java). The message contains event, task, dispatch generation, type and scope IDs; it carries no prompts, media or credentials.

To regenerate the HTTP contract without running the full browser suite, run from the repository root. Use database settings for a dedicated local PostgreSQL 16 database in `.env`; the export starts the API and applies Flyway migrations.

```sh
set -a
source .env
set +a
# Use the verification database if configured; otherwise DATABASE_URL must name the dedicated database.
export DATABASE_URL="${VERIFY_DATABASE_URL:-$DATABASE_URL}"
scripts/java21.sh :api:bootJar
python3 scripts/export-openapi.py
npm ci --prefix packages/contracts
npm run generate --prefix packages/contracts
```

The export disables the Worker and enforces Mock. It normalizes the server URL to `/`. The [full Mock acceptance](../../docs/development.md#验收入口) also regenerates both files. See [phases 1–8](../../docs/archive/contracts-history.md) for historical contract changes.
