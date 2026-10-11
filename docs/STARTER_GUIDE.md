# Starting and running an escalator app

What the apps learned up to October 2026 (catchpoll, howmany, flxify, migratecode, lexie, signoff, betblocks, betbot, dualdialogue, swotcrm, investorlab). The survey is in `plans/feature/01-backport-from-projects.md`.

## Layout

```
<app>/                      git repo root
  app/                      the sbt build
    modules/escalator/      this repo, as a git submodule
    modules/{shared,common,persistence,core,backend,frontend,db}
    project/                Projects.scala (module graph in the header comment), Dependencies, Versions, Commands,
                            Common, DatabaseConfig, JavaVersionCheck, WorktreeGitReader
    source_local.example.sh committed; source_local.sh is gitignored and chmod 600
    .jvmopts  .dockerignore  Dockerfile  docker-entrypoint.sh
  plans/feature/NN-topic.md
  brand/og.html → og.png    1200×630, under 300 KB
```

Module graph:
- `escalatorSharedCommon` ← `shared` (cross)
- `escalatorCommon` ← `common` (BuildInfo) ← `persistence` (generated) ← `core` ← `backend`
- `escalatorWeb` and `escalatorAgent` feed into `backend`
- `escalatorDb` ← `dbgen`

The current reference `project/` set is howmany's (`HOW/howmany/app/project`).

## Shared modules: use these, don't copy them

| Need | Module |
|---|---|
| JDBC helpers, sessions, crypto, cookie auth, CSRF, JSON errors, email outbox | `escalator-web` |
| Platform schema V001–V006 (attributes, users, sessions, api_keys, interactions, workspace_files) | `escalator-web/src/main/resources/escalator/web/sql` |
| LLM agent: Claude CLI/API, Codex, OpenAI; tools, conversations, prompt and event stores, CLI version manager | `escalator-agent` and `escalator-agent-protocol` |
| Generated models, repositories, events | `escalator-db` generator |

If you are about to copy a file from a sibling app, check here first. If it isn't here and a second app needs it, move it into escalator instead of copying it.

## Rules every app follows

1. **Env first, in the same shell.** `source source_local.sh && sbt -batch …`. Use `-batch` in scripts and agents.
2. **Never hand-edit generated code** (files headed `THIS FILE IS AUTO-GENERATED`). Change the migration, then `sbt dbMigrate dbGenerate` (plus `dbPrune` where it's used). Fix generator bugs in escalator; don't paper over them in the app or weaken the schema.
3. **Schema change loop:** add a migration, then `dbMigrate dbGenerate`, wire the table into the hand-written `<App>Repository.scala`, compile, test.
4. **Never edit an applied migration.** After merging main, renumber your migrations above main's highest, regenerate once and commit. Never merge two sets of generated code by hand.
5. **Schema conventions**, enforced by `CodeGenerator.validateSchemaConventions`:
   - `created_at` and `updated_at` are NOT NULL when present.
   - Every PG type has a Scala mapping (`CustomGenerator.customMappings`).
   - No `_<digit>` in column names (`change_24h` breaks the snake_case round trip).
   - Type and discriminator columns use `attribute_type` with an FK and a family CHECK (`to_attribute_column`), never VARCHAR enums.
   - Plural table names give singular models.
   - Pure infrastructure tables go in `excludedTables`.
6. **Secrets** come only from env. Commit `source_local.example.sh`; generate values with `openssl rand -base64 32`.
7. **Ports and databases:**
   - Postgres.app runs on :54320, with `<app>_dev` and `<app>_test`. psql is at `/Applications/Postgres.app/Contents/Versions/latest/bin/psql`.
   - Backend on 32x99, webpack dev server on 32x90. Pick an unused x and record it in the app README. catchpoll and migratecode collide on 32499.
8. **Tests never run against a dev database.** DB tests read a dedicated variable (`<APP>_TEST_DB`, or `ESCALATOR_TEST_DB` in escalator), never `DB_NAME`.
9. **Worktrees:**
   - Use `git worktree add ../<app>-wt-<feature>`, never the shared checkout.
   - Copy the untracked `source_local.sh`.
   - A feature that migrates gets its own DB: `createdb <app>_<feature> -T <app>_dev`.
   - sbt-git needs `WorktreeGitReader`.
10. **Deploy what's on main, from a clean clone** (`../<app>-deploy`, or Coolify from GitHub). A deploy is done when `/api/version` reports your commit and `/healthz` is green.
11. **JDK:** 21+ is accepted, with `javacOptions ++= Seq("--release", "21")`. Scaladoc is disabled build-wide (Quill macros break it on JDK 25).
12. **Pekko classic actors**, not Typed.
13. **Public sites need link previews:**
    - A branded 1200×630 PNG under 300 KB.
    - `og:type`, `og:site_name`, `og:title`, `og:description`, `og:url`, `og:image` (absolute https) with `og:image:width`/`height`/`alt`.
    - `twitter:card=summary_large_image` with title, description and image.
    - Check the tags live after deploying.
14. **Plans** go in `plans/feature/NN-topic.md`; the app README links them.

## Runtime basics every app has

- `/healthz`, and `/api/version` (BuildInfo commit, branch, build time).
- Flyway migrate on boot (`filesystem:` location, `outOfOrder`).
- `ADMIN_EMAIL` seeds the first admin.
- `EMAILER=file` for local email.
- Dockerfile:
  - build: `sbt backend/stage frontend/fullLinkJS`
  - web: `npm ci && npm run build:prod`
  - runtime: temurin 21 JRE, non-root uid 10001, `/data` volume, migrations copied in and applied on boot, `HEALTHCHECK /healthz`
  - build escalator from the submodule, never `git clone` HEAD
- `docker-entrypoint.sh` writes the Claude cliproxy `settings.json` when the app uses the agent (`CLIPROXY_URL`, `CLIPROXY_KEY`).

## Known gaps (next phases)

- **Not yet shared:**
  - passwordless `AuthRoutes`
  - `Routes` (SPA fallback, robots/sitemap/llms.txt, OG meta injection)
  - `ApiKeys`
  - an MCP JSON-RPC core
  - the frontend kit for plain-CSS apps (`Api`, `Session`, `Ui`, `Router`)
  - the agent chat UI and SSE routes
- No app has CI that runs `sbt test`.
- `escalator-starter` itself is still on the older generation. Rebuild it from howmany and these modules.
