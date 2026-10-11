# 01 — Backport what the projects learned into escalator

Survey date: 2026-10-11. Branch: `consolidate/backport-2026-10`.

## Why

New apps are no longer started from `escalator-starter`. Each one is copied from its newest sibling and renamed:

- betbot → signoff → migratecode → betblocks → howmany
- signoff → catchpoll → flxify
- signoff → lexie

On the way, the same code was copied 5–7 times with only the package renamed, and the starter fell two generations behind. This plan pulls the identical code back into escalator modules and makes the starter the newest app again.

## Projects surveyed

| Gen | Projects | Escalator pin |
|---|---|---|
| C (Oct 2026, small JDBC+Pekko apps) | catchpoll, howmany, migratecode, lexie, signoff, betblocks, flxify | bacfe11, flxify 6b1d96c |
| B | betbot | 16be823 |
| A (starter era, Autowire/tailwind) | starter, dualdialogue, swotcrm, investorlab | c510902 / d26b14b / aec0a51 / 0e3c9ea |

Not escalator-based: wishingwell (plain Pekko), receivemx, playvesting, claudeman.

## Step 0 — one escalator line (done on this branch)

Projects were pinned to commits that were not on `main`:
- `feature/h2o-domain-attributes` and `flixify-types` (a linear superset of h2o)
- two commits that existed only inside dualdialogue's submodule (f5ffc4f, d26b14b)

The branch is `origin/main` + `flixify-types` + the dualdialogue commits, plus these fixes:

- **Schema validation (2a7c4fc).** Dualdialogue's fail-fast required `id` first plus a `created_at`/`updated_at` pair. That contradicts the keyed-table and column-order support from the h2o and flixify branches. Kept: NOT NULL timestamps, mapped types, no `_<digit>` columns.
- **PasswordUtil.matches (67d68d2).** It accepted the stored hash as the password. Non-bcrypt stored values (plaintext seeds) now return false.
  - **Betbot impact:** `BB/betbot/modules/db/seed/V102__Insert_team_data.sql:50-51` seeds `encrypted_password = 'password'`. Those logins only worked through the hole. Re-seed with bcrypt hashes before betbot moves to this escalator version, and check production for rows like this.

Verified on flxify (scratch worktree):
- `dbGenerate` output is byte-identical to its pinned 6b1d96c.
- Full `compile` succeeds.
- The validator correctly flagged 15 stray local `parity_snap_*` tables, which are not in flxify's migrations.

Not merged anywhere yet. **Pushing / merging to main is the owner's call.**

## Inventory — what to move back

Ranked by (projects × closeness of the copies). "0 diff" means identical apart from the package name.

### Backend

| # | Feature | Where | Copies | Target |
|---|---|---|---|---|
| 1 | Multi-provider agent wrapper: claude-cli, codex-cli, anthropic-api, openai-api; router, catalog, credentials/cliproxy, tool executor, prompt and event stores, conversation actors | catchpoll, flxify `modules/agent` (27 files, 6.5k lines); older forks in betbot, dualdialogue, swotcrm | catchpoll = flxify apart from the env prefix and `finished` | **escalator-agent** (Phase 1) |
| 2 | Shared agent protocol (`AgentEvent`, envelope, `Usage`, `ToolPresentation`, `EngineModels`, `PromptBlocks`) | catchpoll, flxify, betbot, dualdialogue, swotcrm `shared/protocol/agent` | catchpoll = flxify 100%; bb/dd ~90% | **escalator-agent-protocol** (cross) (Phase 1) |
| 3 | Claude CLI version manager (`ClaudeEngine`: npm install, smoke test, switch, prune) | lexie, signoff, migratecode | identical | escalator-agent `cli` package (Phase 1) |
| 4 | `Db`: Hikari pool + small JDBC helpers, `Row`, jsonb and attribute binders | C H M L S B F | 0–2 diff | **escalator-web** (Phase 1) |
| 5 | `Crypto`: HMAC-peppered bcrypt, tokens, codes, AES-GCM | C H M L S B | 0 diff | escalator-web (Phase 1) |
| 6 | `FutureUtils` | C H M L S B + betbot | 0 diff | escalator-common (Phase 1) |
| 7 | `Sessions` (DB + Caffeine, hashed ids) | C H M S B L | 0–2 diff | escalator-web (Phase 1) |
| 8 | `Outbox` + `FileMailer` (`EMAILER=file`) | H M L S B | 0–3 diff | escalator-web (Phase 1) |
| 9 | `HttpSupport`: `ApiError`, handlers, session cookie, `sameOrigin`, `authenticated`/`requireRole`, `clientIp`, `AttemptLimiter` | C H M L S B (+F limiter) | 2–30 diff | escalator-web, with the cookie name and allowed origins as parameters (Phase 1) |
| 10 | Platform migrations V001–V007: attributes, users+sessions, api_keys, interactions, workspace_files, agent tables | C H M L S B | 0–5 diff | escalator-web resources + starter (Phase 2) |
| 11 | App main: Flyway on boot, `ADMIN_EMAIL` first admin, `/healthz`, `/api/version` | C H M L S B | 5–39 diff | starter template (Phase 2) |
| 12 | Passwordless email-code `AuthRoutes` | B M H (S, C, L variants) | 0–10 | escalator-web (Phase 2) |
| 13 | `Routes`: SPA fallback, robots/sitemap/llms.txt, `__APP_URL__` substitution in index.html, OG meta per route | H B M L S; OG in B C F DT | same skeleton | escalator-web `SiteRoutes` + `OgMeta` (Phase 2) |
| 14 | `ApiKeys` (mint, sha256 lookup, cache, scopes) | C L S H | 22–27 | escalator-web (Phase 2) |
| 15 | MCP server core (stateless JSON-RPC + tool registry) | S F L C BB | rewritten every time | escalator-web `mcp` (Phase 2) |
| 16 | Job queue (`jobs` table, SKIP LOCKED) | catchpoll (flxify table) | 1–2 | later |
| 17 | Stripe gateway with a simulated implementation | dualdialogue, betbot | different designs | later |

### Frontend

| # | Feature | Where | Target |
|---|---|---|---|
| F1 | Kit for plain-CSS apps: `Api`, `Session`, `Ui`, `Router` | howmany = migratecode = betblocks (100% Api/Session); lexie and signoff a second pair | escalator-frontend `kit` package (Phase 2) |
| F2 | Agent chat UI v2: SSE + Last-Event-ID resume, reducer, TurnView, ToolCard, ThreadRail, prompts admin | betbot (most complete), dualdialogue (reducer in shared), swotcrm | escalator-agent-ui (Phase 3), reducer in the protocol module |
| F3 | Markdown (marked + DOMPurify) | bb, dd, swot, lexie, signoff | kit (Phase 2) |
| F4 | `index.html` with OG placeholders + `brand/og.html` → og.png | howmany, betblocks (best) | starter template (Phase 2) |
| F5 | Shared user/error types (`LoginUser`, `NewUser`, `<App>BackendError`) | all 12 | escalator-shared-common (Phase 2) |
| F6 | Webpack/package.json for plain-CSS apps | 5 Family-A apps | starter template (Phase 2) |
| F7 | Component demo pages (about 2.4k lines duplicated in dualdialogue) | starter, dualdialogue | escalator-frontend demo or delete (later) |

Notable finding: escalator's `escalator.app.router` and the tailwind components are not used by any Gen C app.

### Build / ops / docs (starter)

Take howmany/betblocks `app/project/*` as the template:
- `WorktreeGitReader`
- `JavaVersionCheck` accepting 21+
- `javac --release 21`
- slim `plugins.sbt`
- scaladoc disabled
- Projects.scala with a module-graph header and BuildInfo
- `dbPrune`/`dbUnprune`, **together with** Codegen's `args.headOption match` dispatch. The starter treats any argument as `reset`.

Also:
- Untrack `source_local.sh`; ship `source_local.example.sh`.
- Use `.jvmopts` instead of `compile` scripts; add `.dockerignore` and `docker-entrypoint.sh`.
- The Dockerfile must build from the submodule, not `git clone` HEAD.
- Keep a port registry. catchpoll and migratecode both use 32499.
- Add a CI workflow that runs `sbt test`. No project has one.
- Remove the clutter: `publish-to-r2.shX/Y`, `export*`, `r2_exports`, `.gitignorex`.

Learnings for the starter CLAUDE.md are captured in `docs/STARTER_GUIDE.md`.

## Phases

1. **This branch — done 2026-10-11:**
   - **escalator-agent and escalator-agent-protocol** (#1–#3).
     - Verified by moving flxify onto them in a scratch worktree: full compile, 87 tests including Postgres.
   - **escalator-web** (#4–#9, plus platform SQL V001–V006).
     - Verified by moving betblocks onto it: full compile, all betblocks tests, and 15 module tests including Postgres.
   - `FutureUtils` moved to escalator-common.
   - `docs/STARTER_GUIDE.md`.
   - Security fixes: `PasswordUtil` hash-as-password, and SendGrid printing its API key.
   - DB tests are gated on `ESCALATOR_TEST_DB`. An earlier run used `DB_NAME` and wrote 3 test users and 1 outbox row into `betblocks_dev`; those rows were deleted.
2. Platform migrations, auth routes, site routes and OG meta, API keys, MCP core, the frontend kit; rebuild `escalator-starter` as a Gen C app on these modules.
3. Agent chat UI (frontend) + SSE route builder; move betbot, dualdialogue and swotcrm onto escalator-agent.
4. Adopt in each project: bump the submodule, delete the local copy, compile, test. One project per PR.
