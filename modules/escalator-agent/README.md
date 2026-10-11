# escalator-agent

The agent wrapper the apps were copying between each other (betbot → catchpoll → flxify), now in one place.

- **Providers:** `claude-cli` (through the cliproxy or an API key), `anthropic-api`, `codex-cli` and `openai-api`, behind `AgentProvider`. `ProviderRouter` and `ModelCatalog` pick between them.
- **Profiles and tools:**
  - `AgentProfile` / `AgentProfiles`: system prompt, tool policy, required capabilities.
  - `AgentToolExecutor`: in-process tools. `finished` ends a turn without a closing model call.
- **Conversations:** `AgentEngine` (one-shot and conversation APIs) over `AgentConversationManager` / `AgentConversationActor` (Pekko classic). Slot limits are per user and in total.
- **Stores:**
  - `AgentEventStore` and `PromptStore`, each with in-memory and JDBC implementations.
  - The DDL is `src/main/resources/escalator/agent/sql/agent_tables.sql`. It needs the platform `attributes` and `users` migrations.
- **`cli.ClaudeEngine`:** installs, smoke-tests and switches Claude Code CLI versions from npm (from lexie, signoff and migratecode). `ClaudeEngine.resolving(cli, engine)` makes turns pick up a switch on the next turn.
- **Shared protocol:** the frontend↔backend wire types (`AgentEvent`, `Usage`, `ToolPresentation`, `EngineModelsProtocol`, `PromptBlocksProtocol`) live in `escalator-agent-protocol`, cross-built for JVM and Scala.js, in package `escalator.agent.protocol`.

## Settings

`AgentSettings.fromEnv("MYAPP")` reads `MYAPP_AGENT_MODEL`, `MYAPP_AGENT_PROVIDERS`, `MYAPP_CLAUDE_BIN`, `MYAPP_DATA_DIR` and the rest; the full table is in `AgentSettings.scala`. The provider-owned variables are never prefixed: `ANTHROPIC_API_KEY`, `CLAUDE_CONFIG_DIR` (default `~/.claude-cliproxy`), `CODEX_HOME`, `OPENAI_API_KEY` and `OPENAI_BASE_URL`.

## Wiring (project/Projects.scala)

```scala
lazy val escalatorAgentProtocol = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/escalator/modules/escalator-agent-protocol"))
  .settings(Common.baseSettings: _*)
  .settings(libraryDependencies ++= Dependencies.sharedDependencies)   // circe
  .jsConfigure(_.disablePlugins(RevolverPlugin))

lazy val escalatorAgentProtocolJVM: Project = escalatorAgentProtocol.jvm
lazy val escalatorAgentProtocolJS: Project  = escalatorAgentProtocol.js

lazy val escalatorAgent: Project = project
  .in(file("modules/escalator/modules/escalator-agent"))
  .dependsOn(escalatorCommon, escalatorAgentProtocolJVM)
  .settings(Common.jvmSettings: _*)
  .settings(
    libraryDependencies ++= Dependencies.agentDependencies,              // pekko, circe, anthropic-java 2.34.0, openai-java 4.58.0
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test,
    libraryDependencies += "org.apache.pekko" %% "pekko-stream-testkit" % Versions.pekkoVersion % Test
  )
```

Add both to `build.sbt` (`lazy val escalatorAgent = Projects.escalatorAgent`, and the same for the protocol). Have `shared` depend on `escalatorAgentProtocol` if the frontend needs the wire types, and `backend` on `escalatorAgent`.

## Moving an app off its own copy

1. Delete `modules/agent` and `modules/shared/.../protocol/agent`.
2. Rewrite the imports: `io.<app>.agent` → `escalator.agent`, `io.<app>.protocol.agent` → `escalator.agent.protocol`.
3. Change `AgentSettings.fromEnv()` to `AgentSettings.fromEnv("<APP>")`.

Checked on flxify, 2026-10-11: two files changed, the full build compiled, and the module's tests passed, including the Postgres store against `agent_tables.sql`.

## Known limits

- User ids are `Long`; an app with uuid users can't use the JDBC stores yet.
- The chat UI (SSE routes and Laminar components) is still per app (betbot and dualdialogue have the most complete versions). See `plans/feature/01-backport-from-projects.md`, phase 3.
