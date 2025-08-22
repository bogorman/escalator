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

## Database Code Generation System

The database generator (`modules/escalator-db/generators/`) is the core of eSCALAtor's code generation approach. It reads PostgreSQL database schemas and generates complete persistence layers.

### Generation Workflow

1. **Connection & Schema Reading** (`CodeGenerator.scala`)
   - Connects to PostgreSQL using JDBC
   - Reads table metadata, columns, primary keys, foreign keys, unique constraints
   - Supports table inheritance (abstract tables and inherited tables)
   - Excludes system tables (flyway_schema_history, schema_version by default)

2. **Model Generation** 
   - Creates case classes for each table with proper Scala types
   - Generates value classes for ID types (e.g., `UserId`, `ProductId`)
   - Handles nullable columns as `Option[T]`
   - Supports auto-columns (id, created_at, updated_at)
   - Extends `Persisted` trait for all models

3. **Repository Generation** (`GeneratorTemplates.scala`)
   - Creates trait interfaces in `persistence/database/tables/`
   - Generates PostgreSQL implementations in `persistence/postgres/tables/`
   - Includes standard CRUD operations
   - Generates upsert methods based on unique keys
   - Creates specialized update methods for column combinations
   - Handles monitoring and logging automatically

### Key Components

**CodegenOptions** - Configuration for generation:
- Database connection (url, user, password, schema)
- Package names and folder paths
- Excluded tables list
- Custom imports

**Table & Column Classes**:
- `Table`: Represents database table with columns, keys, inheritance
- `Column`: Handles type mapping, nullable, references, unique constraints
- `SimpleColumn`: Lightweight column reference for foreign keys

**Template System** (`GeneratorTemplates.scala`):
- `tableTraitTemplate`: Interface for table operations
- `tableDaoTemplate`: PostgreSQL implementation with Quill
- `genericDatabaseTemplate`: Main database trait combining all tables
- `postgresDatabaseTemplate`: PostgreSQL database implementation

**Code Builders** (`CodeBuilder.scala`, `DefnBuilder.scala`):
- Build upsert operations based on unique keys
- Generate update methods for specific column combinations
- Create getter methods by unique keys
- Handle conflict resolution in upserts

### Generated Operations

For each table, the generator creates:

1. **Basic CRUD**:
   - `getById(id)` - fetch by primary key
   - `update(model)` - update existing record
   - `upsert(model)` - insert or update
   - `delete(model)` - delete record
   - `count` - count all records
   - `getAll()` - fetch all records

2. **Unique Key Operations** (auto-generated based on constraints):
   - `upsertOn[UniqueColumns]` - upsert based on unique key
   - `existsOn[UniqueColumns]` - check existence
   - `getBy[UniqueColumns]` - fetch by unique key
   - `update[Columns]By[UniqueKey]` - update specific columns

3. **Batch Operations**:
   - `upsert(list)` - batch upsert
   - `store(list)` - batch insert

### Customization Points

**CustomGenerator** trait allows:
- Custom type mappings
- Custom SQL-to-Scala type converters
- Additional model processing
- Default value handling

**Type Mapping** (`TypeMapper.scala`):
- PostgreSQL types → Scala types
- Supports UUID, timestamps, JSON, arrays
- Extensible via CustomGenerator

### Running the Generator

The generator is typically invoked from consuming projects with:
```scala
CodeGenerator.run(codegenOptions, customGenerator)
```

With system properties:
- `dbgen.reset=true` - regenerate all files
- `dbgenreset=true` - alternative reset flag

## Development Commands

### Database Code Generation
Configure `CodegenOptions` with your database details and run the generator from your project's build.

### Running Tests
Currently no test framework is configured. Check with the user for the appropriate test command.

### Building the Project
This is a core library included in eSCALAtor projects. Build configuration should be in the consuming project.

### Linting and Type Checking
Ask the user for the appropriate lint and typecheck commands for this Scala project.

## Recent Changes
- Migration from Akka to Apache Pekko (all Akka imports replaced with Pekko equivalents)
- Updated to Java 21
- Enhanced database generation to handle tables without ID, CREATED_AT, and UPDATED_AT columns

## Important Notes
- When modifying actor-based code, use Pekko imports, not Akka
- Database models extend `Persisted` trait
- Frontend components should extend the `Component` trait
- Use existing utilities in `escalator.util` for common operations
- Configuration loading uses PureConfig via `Configuration.fetch`