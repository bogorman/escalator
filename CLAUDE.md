# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

eSCALAtor is a full-stack Scala starter project that has recently migrated from Akka to Apache Pekko. It provides an opinionated architecture with:
- Backend: Scala 2.13, Pekko + Pekko HTTP
- Frontend: Laminar (Scala.js reactive UI framework)
- Database: PostgreSQL with Quill for type-safe queries
- Additional: Monix for reactive programming, Kamon for monitoring

## Architecture

### Module Structure
- `modules/escalator-common/`: Shared backend utilities (auth, email, logging, Pekko actors, PostgreSQL, Redis, websockets, OAuth)
- `modules/escalator-db/generators/`: Database code generation from PostgreSQL schema
- `modules/escalator-frontend/`: Laminar-based frontend application
- `modules/escalator-shared-common/`: Models and validators shared between frontend and backend
- `modules/escalator-backend/`: Backend application code (currently empty)
- `modules/escalator-core/`: Core domain logic (currently empty)
- `modules/escalator-shared/`: Additional shared code (currently empty)

### Key Components

**Database Layer**
- Uses Quill for type-safe database queries
- Code generation from PostgreSQL schema via `modules/escalator-db/generators/`
- **Aggregate generation**: DDD-style aggregate roots with hierarchical state management
- **Event sourcing**: Pekko Persistence compatible pure event handlers
- Flyway for database migrations
- Custom naming strategies and type mappers for Scala/PostgreSQL interop

**Frontend Framework**
- Laminar for reactive UI components
- Custom router implementation in `escalator.app.router`
- WebSocket support via `LaminarWebsocket`
- Actor system integration for frontend state management

**Authentication & Security**
- OAuth support with providers configuration
- JWT-based authentication utilities
- Password hashing and validation

**Common Utilities**
- Pekko actors for concurrent processing
- Redis integration for caching
- Email sending via SendGrid
- Comprehensive logging with custom actor loggers
- WebSocket utilities for real-time communication
- EventBus implementations (PekkoEventBus for production, NullEventBus for testing)

## Database Code Generation System

The database generator (`modules/escalator-db/generators/`) is the core of eSCALAtor's code generation approach. It reads PostgreSQL database schemas and generates complete persistence layers with ES-compatible event system and customizable repositories.

**📚 For complete documentation, see: [DATABASE_GENERATOR.md](DATABASE_GENERATOR.md)**

### Quick Overview

The generator creates:
- Model case classes with proper Scala types
- Repository traits and PostgreSQL implementations
- ES-compatible events (XxxCreated, XxxUpdated, XxxDeleted)
- User-customizable repository classes (never overwritten)

### Key Features

- **Auto-generated operations** based on unique constraints (upsertOnX, getByX, existsOnX)
- **Event publishing** for all CRUD operations with EventBus integration
- **Customizable repositories** in `{packageName}.core.repositories.postgres/`
- **Type-safe queries** using Quill with PostgreSQL support
- **Full monitoring and logging** integration with Kamon

## Development Commands

### Database Code Generation
Configure `CodegenOptions` with your database details and run the generator from your project's build.

### Running Tests
Currently no test framework is configured. Check with the user for the appropriate test command.

### Building the Project
This is a core library included in eSCALAtor projects. Build configuration should be in the consuming project.

### Linting and Type Checking
Ask the user for the appropriate lint and typecheck commands for this Scala project.

## Latest Features

### Automatic AppRepository Generation + ES Events

The generator now creates:
- **Customizable repositories** in `core/repositories/postgres/` (never overwritten)
- **ES-compatible events** for all CRUD operations  
- **Event publishing** integrated into all database operations

Repository example:
```scala
class UsersRepository(database: PostgresDatabase)
  (implicit logger: Logger, monitoring: Monitoring, eventBus: EventBus)
  extends PostgresUsersTable(database) {
  
  // Add custom business logic here
  // Inherits all CRUD + event publishing
  
  import PostgresMappedEncoder._
  import monix.execution.Scheduler.Implicits.global
  import ctx._
}
```

**📚 Complete guide: [DATABASE_GENERATOR.md](DATABASE_GENERATOR.md)**

## Recent Changes
- **NEWEST**: Aggregate generation system for Domain-Driven Design (DDD) patterns
- **NEWEST**: Generated BaseXxxState classes with hierarchical reference tracking
- **NEWEST**: Pure event handlers compatible with Pekko Persistence
- **NEWEST**: State repositories supporting both DB bootstrap and event replay
- **NEWEST**: Automatic aggregate boundary detection for multi-level relationships
- **NEW**: Automatic AppRepository generation (`UsersRepository`, `ProductsRepository`, etc.)
- **NEW**: ES-compatible event system (XxxCreated, XxxUpdated, XxxDeleted events)
- **NEW**: Configurable repository folder structure
- **NEW**: Repository naming follows pluralized convention (e.g., `UsersRepository`)
- **LATEST**: Compile-time event publishing enforcement via EventRequiredResult wrapper
- **LATEST**: Read/write operation separation with dedicated helpers (`read{}`, `write{}`)
- **LATEST**: Consistent model return types across all CRUD operations
- **LATEST**: Efficient PostgreSQL xmax-based upsert pattern for insert/update detection
- **LATEST**: Enhanced monitoring/event publishing separation with chainable API
- Migration from Akka to Apache Pekko (all imports updated)
- Updated to Java 21
- Enhanced generation for tables without standard columns

## Important Notes
- **Repository naming**: Generated as `UsersRepository` (pluralized), not `UserRepository`
- **Package structure**: Repositories placed in `{packageName}.core.repositories.postgres`
- **EventBus required**: All repositories need `EventBus` in implicit scope
- **Never overwritten**: AppRepositories protected once created
- **Events auto-published**: All CRUD operations trigger events via EventRequiredResult
- **Compile-time enforcement**: Write operations must call `.publishingXxx()` methods
- **Read/write separation**: Use `read{}` for queries, `write{}` for mutations
- **Consistent returns**: All operations return full models (not mixed IDs/models)
- **xmax efficiency**: Upserts use PostgreSQL's xmax column for single-query insert/update detection
- **Pekko imports**: Use Pekko, not Akka
- **Database models**: Must extend `Persisted` trait