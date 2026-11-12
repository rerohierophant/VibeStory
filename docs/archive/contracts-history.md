# Shared contracts: phases 1–8

> Historical record. See the [current contract workflow](../../packages/contracts/README.md) for commands. Do not run historical real-provider flags during acceptance.

`openapi.json` is captured from the running Spring API's `/v3/api-docs`. `src/api.d.ts` is generated from it;
do not maintain a second hand-written API schema. The frontend now uses this generated contract for the API; a separate offline demo remains available.

```sh
scripts/java21.sh bootJar
# DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD must refer to a local PostgreSQL 16 database.
python3 scripts/verify-phase1.py --phase2 --phase3 --phase4 --write-contracts
npm ci --prefix packages/contracts
npm run generate --prefix packages/contracts
```

The acceptance script starts and stops its own API processes, creates new fixture accounts/projects and
performs a real SIGKILL/restart. It does not truncate existing data. The captured server URL is normalized
to `/` so the contract does not depend on a random local verification port.

Phase one implements only video tasks. Future task/event/tool contracts belong here when their owning
backend behavior is implemented.

Phase two adds project search/update/delete/overview, scoped episode/story editing, and multipart visual-style
uploads. Create endpoints declare HTTP 201 and task submissions HTTP 202 in the generated schema. Upload
`label` and `image` as multipart form fields; private reference image URLs require the session cookie.

Phase three adds breakdown tasks, scoped attempt diagnostics, editable shots and project asset-name cards.
The shared task schema is now `Task` (video and breakdown), with `type` and `targetId`; `basedOn` / `videoUrl`
are omitted for breakdown tasks. Null optional response fields are omitted, matching the page's optional
fields and generated TypeScript types. Shot status and generation state retain the page's enum values.
Use `--real-text --phase3` only with the local provider environment loaded; it makes one real text request.

Phase four adds asset versions, private uploads, global asset lists and usage, image tasks, selected asset/image
switching and private candidate media. `AssetCard` retains the page's versions/selection fields; `Shot.images`
adds `url` and `guidance`, retaining nullable historical `inputSnapshot`. `Task.imageGuidance` is the submitted
reference capability projection; it never contains credentials or storage keys. An image task produces one candidate.
Default acceptance forces Mock. `--real-image --phase4` explicitly makes one n=1 paid image request and no retry;
the text provider remains Mock unless separately enabled. Image generation supports only MiniMax and Mock;
DeepSeek text keeps its OpenAI-compatible protocol. See [Phase 4 notes](backend-phase4.md) for protocol limitations.

Phase five adds MiniMax video attempts with persisted `externalTaskId`, `Task.resumable`, optional video
regeneration nonce, and video task/source/duration fields in Shot. `GET /api/runtime` reports read-only
model configuration without credentials. The web API client imports these generated schemas. Run
`scripts/export-openapi.py` after bootJar to capture OpenAPI with the Worker disabled, then regenerate.
Browser acceptance (`scripts/verify-web.py`) forces all three providers to Mock. The one-video acceptance
marker must remain in place; do not rerun paid generation for contract or frontend tests.

Phase six (`0.6.0`) adds authenticated `GET /api/task-metrics`, scoped to the current user's tasks created
within 24 hours. Task counts and known-outcome failure rate are separate from attempt timing samples;
unknown / canceled tasks do not enter the failure-rate denominator. The first queue wait excludes retry
queue time; attempt elapsed time includes interruption/recovery. Missing averages/rates are omitted.
`scripts/verify-all-mock.sh` exports and regenerates this contract as part of the free closeout, with
all model keys removed and Mock-only startup enforced. No recording or paid media generation is performed.

Phase seven (`0.7.0`) adds scoped episode generation batches, per-member acceptance / reuse / blockers,
durable batch progress, dispatch metrics and explicit dispatch resume. `requestKey` identifies one batch
submission; repeating it with different content returns 409. A complete batch may contain failures or
blocked members. `regenerate` uses that new request identity for intentional generation.
The internal MQ notification carries schemaVersion 1 plus event/task/dispatchGeneration/type and scope IDs;
it carries no prompts, media, credentials or provider parameters. The owning Java record is
`TaskNotification`; `task-notification.v1.schema.json` is the shared internal transport schema,
separate from the HTTP OpenAPI schema. A contract test guards its field / version / type alignment.
Real RabbitMQ verification is `scripts/verify-phase7-mock.sh`; the database baseline remains
`scripts/verify-all-mock.sh`. Both prohibit real generation. See [Phase 7 notes](backend-phase7.md).

Phase eight (`0.8.0`) adds authenticated `/api/admission-metrics`, scoped per-user bucket counters,
and optional `Task.waitReason/notBefore` and batch member waiting fields. Admission waits preserve
queued/running state and do not create attempts or count as generation failures. No supplier keys or
quota-management API is exposed. Redis is optional and requires rabbit mode; both earlier baselines
explicitly disable it. The internal MQ v1 schema is unchanged. See [Phase 8 notes](backend-phase8.md).
