# 02 — What each project changes to adopt `consolidate/backport-2026-10`

Companion to `01-backport-from-projects.md`. Scanned 2026-10-11.

## Every project

1. Move the `modules/escalator` submodule to the branch, once it is merged to main.
2. `source source_local.sh && sbt -batch dbGenerate`. The schema validator now fails generation on:
   - nullable `created_at` or `updated_at`
   - PG types with no Scala mapping
   - `_<digit>` column names

   Fix the schema, or add pure infrastructure tables to `excludedTables`.
3. Review and commit the regenerated code, then `compile` and `test`.
4. When adopting `escalator-web` or `escalator-agent`:
   - Add the module to `project/Projects.scala` and `build.sbt` (snippets in each module's README).
   - Delete the app's copies.
   - Rewrite the imports.
5. DB tests must read a dedicated `*_TEST_DB` variable, never `DB_NAME`.

## Suggested order

betblocks → howmany → migratecode → catchpoll → flxify → signoff → lexie → betbot → dualdialogue → investorlab → swotcrm → starter. wush is already on the branch (it was built on it).

The first five are near-mechanical. The rest carry generator catch-up or app-specific forks.

## Per project

### betblocks (`AI/betblocks/app`, pin bacfe11) — done in a scratch copy
- **escalator-web:**
  - Delete `db/Db`, `auth/Crypto`, `auth/Sessions`, `http/HttpSupport`, `email/Outbox` and `common/util/FutureUtils`.
  - Import `escalator.web.*` and `escalator.util.FutureUtils`. Files in the `auth`, `http` and `email` packages need the matching `escalator.web.<pkg>._` import.
  - Fully qualified `io.betblocks.db.Db` references also need rewriting.
  - `SupportImpl` gets `val SessionCookieName = "bb_session"` and `override val extraOrigins = HttpSupport.originsFromEnv("BETBLOCKS_ALLOWED_ORIGINS")`.
  - Result: compiled and all tests passed.

### howmany (`HOW/howmany/app`, pin bacfe11)
- **escalator-web:** same steps as betblocks, with cookie `hm_session`.
- Its `HttpSupport` has a `track` hook. Keep `track` in the app and add `override def onError(kind: String) = track(kind)`.

### migratecode (`AI/migratecode/app`, pin bacfe11)
- **escalator-web:** same as betblocks, with cookie `mc_session`.
- **Package clash.** Its own `modules/agent` is in package `escalator.agent` (`AgentEvent`, `AgentProfile`), which collides with escalator-agent. Rename it to `io.migratecode.agent` before depending on escalator-agent.
- **Claude engine.** Replace its `ClaudeEngine` with `escalator.agent.cli.ClaudeEngine`. The constructor is now `(cli: ClaudeCliConfig, baseDir)`, and the smoke test uses the turn's environment. `ClaudeCliRunner`, `TurnSlots` and `ClaudeEnv` stay in the app for now.
- Backend port 32499 clashes with catchpoll; move one of them.

### catchpoll (`domains/catchpoll/app`, pin bacfe11)
- **escalator-agent:**
  - Delete `modules/agent` and `shared/.../protocol/agent`.
  - Rewrite imports to `escalator.agent` / `escalator.agent.protocol`.
  - `AgentSettings.fromEnv()` becomes `fromEnv("CATCHPOLL")`.
  - Its V020 is already the schema `agent_tables.sql` uses.
- **escalator-web:** Db, Crypto, Sessions, HttpSupport and FutureUtils; cookie `catchpoll_session`.
  - It has no `interactions` table, so no Outbox.
  - Its `HttpSupport` has API-key extras (`apiKeys`, `agent(scope)`). Keep those in an app subtrait until ApiKeys is shared (phase 2).
- `DatabaseConfig` uses bare `getenv(...).toInt`. Switch to howmany's `env(k, d)`.

### flxify (`TEST/flxify-backend/backend`, pin 6b1d96c) — agent done in a scratch copy
- **escalator-agent:** same steps as catchpoll, with `fromEnv("FLIXIFY")` in `CollectionAgent.scala` and `AskRoutes.scala` (that call passes the prefix first). Compiled, and 87 tests passed.
- **escalator-web:** only `Db`, which differs from the shared copy by about 10 lines. Diff before swapping. It doesn't use cookie sessions (admin PIN design).
- **Local DB:** 15 stray `parity_snap_*` tables fail the validator. Drop them or exclude them.
- **Generated-code drift:** `V010__People.sql` is committed but its generated code isn't (`dbGenerate` creates Credit, Person and PeopleTitle).
- **uuid ids:** account ids are uuid, so the JDBC agent stores (Long user ids) aren't usable yet.

### signoff (`LAW/signoff/app`, pin bacfe11)
- **escalator-web:** cookie `signoff_session`.
  - Its `PaymentRequired` handling moves into `override val appErrors`.
  - API-key extras stay in an app subtrait.
- **Claude engine:** replace `io.signoff.lexie.ClaudeEngine` with the shared one (new constructor).
- **Empty module:** `modules/agent` is empty but declared in `Projects.scala`. Delete it, or point backend at `escalatorAgent`.
- Its `plans/` and `brand/` are outside the git repo (the repo root is `app/`).

### lexie (`lexie/app`, pin bacfe11)
- **escalator-web:** cookie `lexie_session`.
  - `BudgetExceeded`, `SharePrivate` and `PaymentRequired` handling moves into `appErrors`.
  - `requirePermission` and `agent(scope)` stay in an app subtrait.
  - Its `Sessions` and `SessionUser` add `can(permission)`. Keep its own Sessions until the shared one supports permissions.
- **Claude engine:** replace `lexie-core/engine/ClaudeEngine` with the shared one. `AdminRoutes` `/api/admin/engine` keeps working.
- `README.md` is a stale copy of signoff's.

### betbot (`BB/betbot`, pin 16be823)
- **Generator catch-up:** picks up 6b1d96c (real, uuid[] and tsvector columns; keyword names; any column order) and the validator. Regenerate and review.
- **Passwords:** the `PasswordUtil` fix breaks plaintext seed logins. `seed/V102__Insert_team_data.sql:50-51` seeds `'password'` for `barry@betbet.io` and `dekoatwork@gmail.com`.
  - Reseed with bcrypt hashes.
  - **Check production** for non-bcrypt `encrypted_password` rows; those accounts accept the password `password` today.
- **FutureUtils:** switch to `escalator.util.FutureUtils`.
- **Agent:** its fork (per-user keys, tiers, artifacts, prompt layers) is the richest. Fold it into escalator-agent in phase 3 rather than swapping now.

### dualdialogue (`DT/dualdialogue`, pin d26b14b)
- **Generator catch-up:** its local commits are in the branch. It newly picks up 779d8fe (FK finder types), 16be823 (composite identities) and 6b1d96c. Regenerate and review the diff.
- **Validator relaxed:** id-first and the timestamp-presence checks were removed.
- **Passwords:** `PasswordUtil` (10 calls) has no plaintext seeds. Still confirm production rows are all bcrypt.
- **Agent:** its fork (Claude CLI only, TurnRecovery, files, artifacts) goes to phase 3.
- **Demo pages:** about 2.4k lines of component demo pages in `frontend-admin`, duplicated from the starter, can be deleted.

### investorlab (`IL/investorlab`, pin 0e3c9ea)
- **Generator catch-up:** picks up 779d8fe, 16be823 and 6b1d96c plus the validator. Regenerate and review.
- **Passwords:** `seed/V004__Insert_user_data.sql:67` seeds `'password'`. Reseed with bcrypt and check production.
- **Seed versions:** they collide with migrations (V001–V021). Renumber them.

### swotcrm (`SWOT/swotcrm`, pin aec0a51, 22 commits behind)
- **First:** its submodule has **uncommitted** changes to `EmailConf.scala` and `SmtpMailer.scala` (−132 / +55). Upstream or commit them before moving the pin, or they are lost.
- **Breaking generator and library changes:**
  - `Timestamp` constructor is now private (8b9ca48); call sites must pick a unit.
  - Generated actor bases use `DynamicActorLogging` (1e1ac21).
  - `CodegenOptions` gained `storeFullObjectTables` (198ee5d); positional construction in `Codegen.scala` breaks.
  - upsertOn changes (4d438d3, f70bbc0).
  - FK finder types (779d8fe).
  - Regenerate and fix the call sites.
- **Passwords:** plaintext `'password'` seeds in V100, and in V103 for `ian.tully@swotlegal.com`. Reseed and **check production**.
- **Versions:** Scala 2.13.16 → 2.13.17. Agent v1/v2 goes to phase 3.

### wush (`domains/wush`, being ported from Node; worktrees `wush-wt-escalator`, `wush-wt-merge`, `wush-wt-p2`, pin 4aed389)
- **First native consumer.** It is built straight on `escalator-web` and `escalator-agent` (backend `dependsOn(core, escalatorWeb, escalatorAgent)`) and has no copies of Db, Crypto, Sessions, HttpSupport, Outbox or ClaudeEngine. There is nothing to swap.
- **Blocked on deploy until this branch is on GitHub.** Coolify can't fetch 4aed389. Wush's plan 13 §1 has a fallback.
- **Pin moves.** Each escalator commit after 4aed389 means moving the pin in all three worktrees. Keep them on one commit.
- **Shared later.** It adds one more copy of three pieces that phase 2 should share:
  - `http/AuthRoutes.scala` (email-code sign-in)
  - frontend `Api` / `Session` / `Router`
  - a Catchpoll client (`pipeline/Catchpoll.scala`)
  
  When those land, its migration is like betblocks'.
- **Agent wrapper:** `pipeline/Llm.scala` wraps the agent providers into a one-shot "prompt + JSON schema + tools + cwd" call with progress events. migratecode's `ClaudeCliRunner` does the same job. That makes it a candidate for an `escalator-agent` one-shot API (phase 3).

### escalator-starter (`ES/escalator-starter`, pin c510902) — rebuild, don't patch (phase 2)
- **Template:** start from howmany's `project/` set, `escalator-web`, `escalator-agent`, and the platform SQL V001–V006.
- **Remove:** the demo tables and pages.
- **Seeds:** fix the plaintext seed (`seed/V100__Insert_seed_data.sql:63`).
- **Codegen:** port the `Codegen` argument dispatch before adding `dbPrune`. Today any argument means `reset`.
- **Dockerfile:** build escalator from the submodule, not `git clone`.
- **Secrets:** untrack `source_local.sh`, which contains secrets, and commit `source_local.example.sh`.

## Not affected
wishingwell, receivemx, playvesting and claudeman are not escalator-based.
