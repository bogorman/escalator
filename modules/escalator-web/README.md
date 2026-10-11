# escalator-web

The small JDBC + Pekko HTTP platform that the Gen C apps were each carrying a copy of: catchpoll, howmany, migratecode, lexie, signoff, betblocks and flxify. Before this module, every copy differed only in its package name.

| Package | What | Notes |
|---|---|---|
| `db.Db`, `db.Row` | Hikari pool and small JDBC helpers: `query`/`one`/`update`/`insertId`/`tx`/`async`; binders for jsonb (`JsonB`), `attribute_type` (`Attr`) and `text[]` (`TextArray`) | Next to the generated Quill repositories, for joins and locks |
| `auth.Crypto` | Peppered bcrypt (HMAC pre-hash), tokens, 6-digit codes, keyed hash, AES-GCM for strings and bytes | Never accepts a stored hash or a plaintext row as the password |
| `auth.Sessions`, `SessionUser` | Sessions in `user_sessions`, with a 5-minute Caffeine cache | Only the keyed hash of the session id is stored |
| `http.HttpSupport` | `ApiError` with JSON errors, exception and rejection handlers, session cookie, `sameOrigin` CSRF check, `authenticated`, `requireRole`, `clientIp` | Hooks: `SessionCookieName`, `extraOrigins`, `appErrors`, `onError`, `uploadLimitMessage` |
| `http.AttemptLimiter` | In-memory sliding-window limiter for login and code endpoints | |
| `email.Outbox`, `FileMailer` | Email queued in `interactions`; a scheduled sender retries for 2 days | `EMAILER=file` writes `.html` files to `EMAIL_OUTPUT_DIR` |

`escalator.util.FutureUtils` (bounded fan-out) moved to escalator-common.

## Platform migrations

`src/main/resources/escalator/web/sql/V001–V006` is the shared schema:
- V001: attributes (`attribute_type`, `seed_attributes`)
- V002: users and sessions
- V003: api_keys
- V004: interactions
- V005: workspace_files
- V006: platform columns as attributes, plus the `to_attribute_column` and `register_open_domain_attribute` helpers

A new app copies them into `modules/db/migration`. App migrations start at V010. If the app uses the agent, add `escalator-agent/.../agent_tables.sql` as V007.

## Wiring (project/Projects.scala)

```scala
lazy val escalatorWeb: Project = project
  .in(file("modules/escalator/modules/escalator-web"))
  .dependsOn(escalatorCommon)
  .settings(Common.jvmSettings: _*)
  .settings(
    libraryDependencies ++= Dependencies.backendDependencies,   // pekko-http(+circe), hikari, caffeine, jbcrypt, postgresql
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test,
    libraryDependencies += "org.apache.pekko" %% "pekko-http-testkit" % Versions.pekkoHttpVersion % Test,
    libraryDependencies += "org.apache.pekko" %% "pekko-stream-testkit" % Versions.pekkoVersion % Test
  )
// backend .dependsOn(core, escalatorWeb); build.sbt: lazy val escalatorWeb = Projects.escalatorWeb
```

The app supplies its cookie name and allowed origins:

```scala
final class SupportImpl(val sessions: Sessions, val secureCookies: Boolean, val appUrl: String) extends HttpSupport {
  val SessionCookieName = "bb_session"
  override val extraOrigins = HttpSupport.originsFromEnv("BETBLOCKS_ALLOWED_ORIGINS")
}
```

## Moving an app off its own copy

1. Delete the app's copies: `db/Db.scala`, `auth/Crypto.scala`, `auth/Sessions.scala`, `http/HttpSupport.scala`, `email/Outbox.scala` and `util/FutureUtils.scala`.
2. Import `escalator.web.{db,auth,http,email}` instead.
3. Add the two lines above to the app's `HttpSupport`.

Checked on betblocks, 2026-10-11: the full build compiled and all tests passed.

## Tests

`PlatformDbSpec` runs only when `ESCALATOR_TEST_DB` names a **throwaway** database with V001–V006 applied. It never uses `DB_NAME`, because in an app's `source_local.sh` that is the dev database.

```sh
createdb escalator_web_test && for f in src/main/resources/escalator/web/sql/V00*.sql; do psql -d escalator_web_test -f $f; done
ESCALATOR_TEST_DB=escalator_web_test sbt escalatorWeb/test
```
