# eSCALAtor Database Generator Documentation

## Overview

The eSCALAtor database generator reads PostgreSQL schemas and generates complete persistence layers with:
- Model case classes with proper Scala types
- Repository interfaces and implementations
- ES-compatible event system for all CRUD operations
- User-customizable repository classes

## Quick Start

### 1. Configure Generator

```scala
val codegenOptions = CodegenOptions(
  packageName = "com.yourapp",
  appName = "YourApp",
  appFolder = "path/to/app",
  modelsBaseFolder = "path/to/models",
  persistenceBaseFolder = "path/to/persistence",
  databaseFolder = "path/to/database",
  postgresFolder = "path/to/postgres",
  
  // Database connection
  user = "postgres",
  password = "postgres",
  url = "jdbc:postgresql:postgres",
  schema = "public",
  
  // Optional features (all enabled by default)
  generateEvents = true,
  generateAppRepositories = true,
  repositoriesFolder = "modules/core/src/main/scala/com/yourapp/core/repositories/postgres",
  
  // Excluded tables
  excludedTables = List("schema_version", "flyway_schema_history")
)
```

### 2. Create Custom Generator (Optional)

```scala
val customGen = new CustomGenerator {
  def setup(): Boolean = true
  
  def processFileData(fileData: String): String = fileData
  
  def customTypes(): List[String] = List.empty
  
  def customMappers(tableClass: String): String = ""
  
  def customMappings(): Map[String,String] = Map.empty
  
  def useDefaultValue(tableName: String, columnName: String): Boolean = false
  
  // Control event generation per table
  override def shouldGenerateEvents(tableName: String): Boolean = {
    !tableName.startsWith("audit_")  // Skip audit tables
  }
}
```

### 3. Run Generator

```scala
CodeGenerator.run(codegenOptions, customGen)
```

### 4. Add EventBus to Your Application

```scala
// In your application setup
implicit val eventBus: EventBus = PekkoEventBus(EventBusConfig())

// Or use NullEventBus for testing/development without event processing
implicit val eventBus: EventBus = NullEventBus.create()
```

## Generated File Structure

For a database with `users` and `products` tables:

```
modules/core/src/main/scala/com/yourapp/
├── models/
│   ├── User.scala                              # Model case class
│   ├── Product.scala
│   └── events/                                 # ES-compatible events
│       ├── UserEvent.scala                     # UserCreated, UserUpdated, UserDeleted
│       └── ProductEvent.scala
│
├── core/repositories/postgres/                 # User-customizable repositories
│   ├── UsersRepository.scala                   # Never overwritten once created
│   └── ProductsRepository.scala
│
└── persistence/
    ├── YourAppDatabase.scala                   # Main database trait
    ├── database/tables/
    │   ├── UsersTable.scala                    # Table interfaces
    │   └── ProductsTable.scala
    └── postgres/
        ├── PostgresYourAppDatabase.scala       # Database implementation
        └── tables/
            ├── PostgresUsersTable.scala        # PostgreSQL implementations (publishes events)
            └── PostgresProductsTable.scala
```

## Key Features

### 1. Automatic AppRepository Generation

Generated repositories follow this pattern:

```scala
package com.yourapp.core.repositories.postgres

class UsersRepository(database: PostgresDatabase)
  (implicit logger: Logger, monitoring: Monitoring, eventBus: EventBus)
  extends PostgresUsersTable(database) {
  
  // Add custom business logic here
  // Inherits all CRUD operations from PostgresUsersTable
  
  import PostgresMappedEncoder._
  import monix.execution.Scheduler.Implicits.global
  import ctx._
}
```

**Key Points:**
- Class name is pluralized (e.g., `UsersRepository` not `UserRepository`)
- Package is always `{packageName}.core.repositories.postgres`
- Never overwritten once created (protected by `writeIfDoesNotExist`)
- Inherits all standard CRUD operations

### 2. ES-Compatible Event System

For each model, generates events like:

```scala
sealed trait UserEvent extends Event with PersistentEvent {
  def user: User
  def id: UserId
  def correlationId: CorrelationId
  def timestamp: Timestamp
}

case class UserCreated(
  user: User,
  id: UserId,
  correlationId: CorrelationId,
  timestamp: Timestamp
) extends UserEvent

case class UserUpdated(
  user: User,
  previousUser: Option[User] = None,
  id: UserId,
  correlationId: CorrelationId,
  timestamp: Timestamp
) extends UserEvent

case class UserDeleted(
  user: User,
  id: UserId,
  correlationId: CorrelationId,
  timestamp: Timestamp
) extends UserEvent
```

**Event Publishing:**
- Events automatically published after successful DB operations
- Correlation IDs generated for tracing
- Async publishing doesn't block DB operations
- Failures logged but don't break operations

### 3. Generated Operations

For each table, the generator creates:

**Basic CRUD:**
- `getById(id)` - Fetch by primary key
- `update(model)` - Update existing record
- `upsert(model)` - Insert or update
- `delete(model)` - Delete record
- `count` - Count all records
- `getAll()` - Fetch all records

**Unique Key Operations** (auto-generated based on constraints):
- `upsertOn[UniqueColumns]` - Upsert based on unique key
- `existsOn[UniqueColumns]` - Check existence
- `getBy[UniqueColumns]` - Fetch by unique key
- `update[Columns]By[UniqueKey]` - Update specific columns

**Batch Operations:**
- `upsert(list)` - Batch upsert
- `store(list)` - Batch insert

## Configuration Options

### CodegenOptions

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `packageName` | String | Required | Base package for generated code |
| `appName` | String | Required | Application name |
| `generateEvents` | Boolean | `true` | Generate ES-compatible events |
| `generateAppRepositories` | Boolean | `true` | Generate customizable repositories |
| `repositoriesFolder` | String | `""` | Custom path for repositories |
| `excludedTables` | List[String] | `["schema_version", "flyway_schema_history"]` | Tables to skip |

### EventBus Implementations

#### PekkoEventBus (Production)
Full-featured event bus for production use with Pekko actors:
```scala
implicit val eventBus: EventBus = PekkoEventBus(EventBusConfig(
  enableEvents = true,      // Enable/disable event publishing
  bufferSize = 1000,        // Event buffer size
  asyncProcessing = true,   // Async vs sync processing
  logEvents = false         // Log all events
))
```

#### NullEventBus (Testing/Development)
No-op implementation that discards all events - perfect for testing or development:
```scala
// Simple creation
implicit val eventBus: EventBus = NullEventBus.create()

// With custom execution context
implicit val eventBus: EventBus = NullEventBus()(executionContext)

// Use cases:
// - Unit testing repository logic without event side effects
// - Development when event infrastructure isn't ready
// - Performance testing to isolate database operations
// - Feature toggles to conditionally disable events
```

Benefits of NullEventBus:
- **Zero overhead**: No processing, storage, or resource consumption
- **Same API**: Code works identically with any EventBus implementation
- **Thread-safe**: All operations are safe and non-blocking
- **Testing isolation**: Focus on repository logic without event concerns

## Usage Examples

### Custom Repository Logic

```scala
class UsersRepository(database: PostgresDatabase)
  (implicit logger: Logger, monitoring: Monitoring, eventBus: EventBus)
  extends PostgresUsersTable(database) {
  
  // Add validation with event publishing
  override def upsert(user: User): EventRequiredResult[User] = {
    // Validation happens before database operation
    validateEmail(user.email) match {
      case Right(_) => 
        super.upsert(user)  // Returns EventRequiredResult[User]
      case Left(error) => 
        throw new ValidationException(error)
    }
  }
  
  // Custom read operations
  def findByEmailDomain(domain: String): Future[List[User]] = monitored("find_by_email_domain") {
    read {
      ctx.run(
        query[User].filter(_.email like lift(s"%@$domain"))
      ).runToFuture
    }
  }
  
  // Custom write operations with events
  def promoteToAdmin(userId: UserId): EventRequiredResult[User] = monitored("promote_to_admin") {
    write(/* will be set after DB operation */) {
      ctx.run(
        query[User]
          .filter(_.id == lift(userId))
          .update(_.role -> lift("admin"), _.updatedAt -> lift(TimeUtil.nowTimestamp()))
          .returning(u => u)
      ).runToFuture
    }
  }
  
  // Usage in application code:
  // userRepo.promoteToAdmin(userId)
  //   .publishingUpdated((u, prev, cid, t) => UserPromotedToAdmin(u, prev, u.id, cid, t))
}
```

### Efficient Upsert with Event Detection

The generator now uses PostgreSQL's `xmax` system column to efficiently determine whether an upsert was an insert or update:

```scala
// Generated code uses this pattern internally
ctx.run(
  query[User]
    .insertValue(lift(user))
    .onConflictUpdate(_.id)((t, e) => t -> e)
    .returning(r => (r, infix"xmax = 0".as[Boolean]))
).runToFuture.flatMap { case (result, wasInserted) =>
  if (wasInserted) {
    // xmax = 0 means this was an insert
    publishCreatedEvent(result)
  } else {
    // xmax != 0 means this was an update
    publishUpdatedEvent(result)
  }
}
```

This eliminates the need for a SELECT query before the upsert, improving performance.

### Event Subscriptions

```scala
implicit val eventBus: EventBus = PekkoEventBus()

// Subscribe to specific events
eventBus.subscribe[UserCreated] { event =>
  sendWelcomeEmail(event.user)
}

// Subscribe to all events for a model
eventBus.subscribe[UserEvent] { event =>
  auditLog.record(event)
}

// Subscribe with error handling
eventBus.subscribeWithErrorHandling[UserUpdated](
  handler = event => updateSearchIndex(event.user),
  errorHandler = (event, error) => logger.error(s"Failed to update search index", error)
)
```

### Testing

```scala
// Use NullEventBus for testing without event processing
implicit val eventBus = NullEventBus.create()

val userRepo = new UsersRepository(testDatabase)
val result = userRepo.store(testUser).futureValue
// Events are discarded, focus on testing repository logic

// Or use a mock/spy EventBus for testing event publishing
implicit val eventBus = mock[EventBus]
when(eventBus.publish(any[Event])).thenReturn(Future.successful(()))

val userRepo = new UsersRepository(testDatabase)
val result = userRepo.store(testUser).futureValue

// Verify events were published
verify(eventBus, times(1)).publish(argThat { event: Event =>
  event.isInstanceOf[UserCreated] && 
  event.asInstanceOf[UserCreated].user.email == testUser.email
})
```

## Database Requirements

### Expected Conventions

The generator assumes Rails-like conventions:
- Primary key: `id` (SERIAL or UUID)
- Timestamps: `created_at`, `updated_at` (TIMESTAMP)
- Foreign keys follow pattern: `table_name_id`

### Table Inheritance

Supports PostgreSQL table inheritance:
```sql
CREATE TABLE vehicles (
  id SERIAL PRIMARY KEY,
  brand VARCHAR(100)
);

CREATE TABLE cars (
  model VARCHAR(100)
) INHERITS (vehicles);
```

## Architecture

### Module Structure

- `modules/escalator-db/generators/` - Core generator code
  - `CodeGenerator.scala` - Main orchestrator
  - `GeneratorTemplates.scala` - Code templates
  - `CodeBuilder.scala` - Dynamic code builders
  - `DefnBuilder.scala` - Interface definitions
  - `CustomGenerator.scala` - Customization trait
  - `TypeMapper.scala` - SQL to Scala type mappings

### Generation Flow

1. **Connection** - Connect to PostgreSQL via JDBC
2. **Schema Reading** - Read tables, columns, keys, constraints
3. **Model Generation** - Create case classes with proper types
4. **Repository Generation** - Create traits and implementations
5. **Event Generation** - Create ES-compatible events
6. **App Repository Generation** - Create customizable repositories

## Customization

### Custom Type Mappings

```scala
class MyCustomGenerator extends CustomGenerator {
  override def customMappings(): Map[String, String] = Map(
    "jsonb" -> "io.circe.Json",
    "geometry" -> "com.vividsolutions.jts.geom.Geometry"
  )
  
  override def customMappers(tableClass: String): String = {
    if (tableClass == "LocationsTable") {
      """
      implicit val geometryEncoder: Encoder[Geometry] = ...
      implicit val geometryDecoder: Decoder[Geometry] = ...
      """
    } else ""
  }
}
```

### Per-Table Control

```scala
override def shouldGenerateEvents(tableName: String): Boolean = {
  tableName match {
    case t if t.startsWith("audit_") => false  // No events for audit tables
    case t if t.startsWith("temp_") => false   // No events for temp tables
    case _ => true
  }
}
```

## Migration Path

### From Existing Projects

1. **No breaking changes** - Existing code continues working
2. Update `CodegenOptions` with new settings
3. Run generator
4. Add `EventBus` to dependency injection
5. Start using events and custom repositories

### Toward Event Sourcing

**Phase 1 (Current):** DB-triggered events
- Events published after DB operations
- Repository layer handles publishing

**Phase 2 (Future):** Command-sourced events
- Move publishing to command handlers
- Same event types, different trigger

**Phase 3 (Future):** Full Event Sourcing
- Replace repositories with event stores
- Events become source of truth

## Troubleshooting

### Common Issues

**"EventBus not found"**
- Add to implicit scope: `implicit val eventBus: EventBus = PekkoEventBus()`

**"Repository not generated"**
- Check `generateAppRepositories = true`
- Check if file already exists (won't overwrite)
- Verify `repositoriesFolder` path is correct

**"Events not publishing"**
- Check `generateEvents = true`
- Verify `shouldGenerateEvents()` returns true
- Check `EventBusConfig.enableEvents = true`

**"Compilation errors"**
- Ensure all imports are available
- Check EventBus is in implicit scope
- Verify CorrelationId type is imported

**"EventRequiredResult not found" (New)**
- Import: `import escalator.util.postgres.EventPublishing._`
- Ensure RepositoryHelpers trait is extended
- Check that EventPublishing.scala is on classpath

**"Cannot call .publishingXxx method" (New)**
- Method only available on EventRequiredResult[T]
- Use `write(model) { dbOp }` for write operations
- Use `read { dbOp }` for read operations

**"Return type mismatch" (New)**
- Update method signatures to return EventRequiredResult[Model] for writes
- Update method signatures to return Future[Option[Model]] or Future[Model] for reads
- Check that you're calling the right publishing method (.publishingCreated vs .publishingUpdated)

**"xmax column not found"**
- Requires PostgreSQL (xmax is a system column)
- Check that Quill supports `infix` queries in your version
- Verify PostgreSQL driver version compatibility

### Debugging

Enable event logging:
```scala
val eventBus = PekkoEventBus(EventBusConfig(logEvents = true))
```

Check generation logs:
```scala
// Generator prints to console during generation
// Look for lines like:
// generateAppRepositories: User
// generateModelEvents: User
```

## Performance Considerations

- **Events are async** - Don't block DB operations
- **Batch operations** - Use `upsert(list)` for bulk inserts
- **Connection pooling** - Configure HikariCP appropriately
- **Event buffering** - Adjust `bufferSize` for high throughput
- **Efficient upserts** - Uses PostgreSQL's `xmax = 0` check to determine insert vs update in a single query, eliminating the need for a separate SELECT before upsert operations

## Dependencies

Uses existing eSCALAtor infrastructure:
- Apache Pekko (formerly Akka)
- Quill for type-safe queries
- PostgreSQL JDBC driver
- Monix for reactive programming
- ScalaFmt for code formatting

## Aggregate Generation System (NEW)

The generator now supports creating **Domain-Driven Design (DDD) aggregate roots** from your database schema. This feature generates aggregate state classes and event handlers for building event-sourced systems while maintaining compatibility with both DB-based and Pekko Persistence architectures.

### What are Aggregates?

An aggregate is a cluster of related entities and value objects that are treated as a single unit for data consistency. In this system:

- **User Aggregate**: Contains user data + their posts + comments on those posts
- **Order Aggregate**: Contains order data + order items + payments + shipments  
- **Product Aggregate**: Contains product data + reviews + inventory + pricing history

### Quick Start with Aggregates

#### 1. Enable Aggregate Generation

```scala
val codegenOptions = CodegenOptions(
  // ... existing options ...
  
  // Enable aggregate generation
  generateAggregates = true,
  aggregatesFolder = "modules/core/src/main/scala/aggregates",
  
  // Specify which tables are aggregate roots
  aggregateRootTables = List("users", "orders", "products"),
  
  // Optional: limit traversal depth to avoid performance issues
  maxAggregateDepth = 3
)
```

#### 2. Generate Specific Aggregate

```scala
// Generate User aggregate starting from users table
CodeGenerator.generateAggregate(codegenOptions, customGen, "users")

// Generate Order aggregate starting from orders table  
CodeGenerator.generateAggregate(codegenOptions, customGen, "orders")
```

### Generated Aggregate Structure

For a User aggregate (user → posts → comments), the generator creates:

```
aggregates/user/
├── BaseUserState.scala          # Aggregate state model (generated)
├── UserEventHandler.scala       # Event handlers (generated)  
├── UserStateRepository.scala    # State loading/replay (generated)
├── UserAggregate.scala          # Pekko Persistence actor (optional)
└── custom/
    ├── UserState.scala          # User extensions (never overwritten)
    └── UserBusinessLogic.scala  # Custom logic (never overwritten)
```

### Example: Generated User Aggregate

#### BaseUserState.scala
```scala
case class BaseUserState(
  user: User,
  
  // Direct children (store IDs only)
  postIds: List[PostId] = List.empty,
  messageIds: List[MessageId] = List.empty,
  orderIds: List[OrderId] = List.empty,  // Reference to separate aggregate
  
  // Nested children (efficient Maps for lookups)
  postComments: Map[PostId, List[CommentId]] = Map.empty,
  postLikes: Map[PostId, List[LikeId]] = Map.empty,
  
  // Event sourcing metadata
  version: Long = 0L,
  lastUpdated: Timestamp = Timestamp(0L)
) {
  def applyEvent(event: Event): BaseUserState = event match {
    case e: UserEvent => UserEventHandler.apply(this, e)
    case e: PostEvent => UserEventHandler.apply(this, e)
    case e: CommentEvent => UserEventHandler.apply(this, e)
    case _ => this
  }
}
```

#### UserEventHandler.scala  
```scala
object UserEventHandler {
  
  // Handle User events
  def apply(state: BaseUserState, event: UserEvent): BaseUserState = event match {
    case UserCreated(user, _, correlationId, timestamp) =>
      state.copy(
        user = user,
        version = state.version + 1,
        lastUpdated = timestamp
      )
      
    case UserUpdated(user, _, _, correlationId, timestamp) =>
      state.copy(
        user = user,
        version = state.version + 1,
        lastUpdated = timestamp
      )
  }
  
  // Handle Post events (direct children)
  def apply(state: BaseUserState, event: PostEvent): BaseUserState = event match {
    case PostCreated(post, _, correlationId, timestamp) if post.userId == state.user.id =>
      state.copy(
        postIds = state.postIds :+ post.id,
        version = state.version + 1,
        lastUpdated = timestamp
      )
  }
  
  // Handle Comment events (nested children through Post)
  def apply(state: BaseUserState, event: CommentEvent): BaseUserState = event match {
    case CommentCreated(comment, _, correlationId, timestamp) 
      if state.postIds.contains(comment.postId) =>
      val comments = state.postComments.getOrElse(comment.postId, List.empty)
      state.copy(
        postComments = state.postComments + (comment.postId -> (comments :+ comment.id)),
        version = state.version + 1,
        lastUpdated = timestamp
      )
  }
}
```

#### UserStateRepository.scala
```scala
class UserStateRepository(
  usersRepo: UsersRepository,
  postsRepo: PostsRepository,
  commentsRepo: CommentsRepository
)(implicit ec: ExecutionContext, logger: Logger) extends UserEventHandler {
  
  /**
   * Bootstrap complete aggregate state from database
   */
  def loadUserState(userId: UserId): Task[Option[BaseUserState]] = {
    for {
      userOpt <- Task.fromFuture(usersRepo.getById(userId))
      state <- userOpt match {
        case None => Task.pure(None)
        case Some(user) =>
          for {
            // Load direct children in parallel
            (posts, messages) <- Task.parMap2(
              Task.fromFuture(postsRepo.getByUserId(userId)),
              Task.fromFuture(messagesRepo.getByUserId(userId))
            )
            
            // Load nested children (all comments for user's posts)
            allComments <- if (posts.nonEmpty) {
              Task.fromFuture(commentsRepo.getByPostIds(posts.map(_.id)))
            } else {
              Task.pure(List.empty)
            }
            
            // Build nested structure
            postCommentsMap = allComments.groupBy(_.postId).map {
              case (postId, comments) => postId -> comments.map(_.id)
            }
            
          } yield Some(BaseUserState(
            user = user,
            postIds = posts.map(_.id),
            messageIds = messages.map(_.id),
            postComments = postCommentsMap
          ))
      }
    } yield state
  }
  
  /**
   * Replay events to rebuild state (Pekko Persistence compatible)
   */
  def replayUserState(userId: UserId, fromSequenceNr: Long = 0L): Task[BaseUserState] = {
    // Implementation depends on event store
    // This demonstrates the pure event application pattern
    val events: List[Event] = loadEventsFromStore(userId, fromSequenceNr)
    
    val initialState = BaseUserState.empty(userId)
    Task.pure(events.foldLeft(initialState)(_.applyEvent(_)))
  }
}
```

### Key Features

#### 1. **Hierarchical Reference Tracking**
- Automatically discovers nested relationships (User → Post → Comment)
- Stores IDs instead of full objects for efficiency
- Uses Maps for O(1) lookup of nested collections

#### 2. **Aggregate Boundary Detection**
- Smart detection of aggregate boundaries to avoid crossing into other aggregates
- Tables like `users`, `orders`, `products` are treated as separate aggregates
- Cross-aggregate references stored as IDs only

#### 3. **Dual-Mode Compatibility**
- **DB Bootstrap**: Load current state from database
- **Event Replay**: Rebuild state by replaying events (Pekko Persistence)
- **Hybrid**: Load snapshot + replay recent events for performance

#### 4. **Pure Event Handlers**
- All event handlers are pure functions: `(State, Event) => State`
- No side effects, perfect for event sourcing
- Idempotent - applying same event twice is safe

#### 5. **Performance Optimizations**
- Batch loading to minimize database queries
- Parallel loading where possible  
- Efficient Map structures for nested lookups
- Optional snapshot support for large aggregates

### Pekko Persistence Integration

The generated aggregates are fully compatible with Pekko Persistence:

```scala
class UserAggregate(userId: UserId) extends EventSourcedBehavior[Command, Event, BaseUserState] {
  
  override def persistenceId: PersistenceId = 
    PersistenceId.ofUniqueId(s"User-${userId.value}")
  
  override def emptyState: BaseUserState = BaseUserState.empty(userId)
  
  // Command handling
  override def commandHandler: CommandHandler[Command, Event, BaseUserState] = {
    CommandHandler.fromFunction { (state, command) =>
      command match {
        case CreateUser(userData) =>
          Effect.persist(UserCreated(userData, userId, correlationId, Timestamp.now))
        case CreatePost(postData) =>
          Effect.persist(PostCreated(postData, postData.id, correlationId, Timestamp.now))
      }
    }
  }
  
  // Event handling - delegates to generated handlers
  override def eventHandler: EventHandler[BaseUserState, Event] = {
    EventHandler.fromFunction(_.applyEvent(_))
  }
}
```

### Migration Path

The aggregate system supports gradual migration:

```scala
// Step 1: Current DB-based system
val state = userStateRepo.loadUserState(userId)

// Step 2: Add event publishing (already done by existing generator) 
userRepo.update(user).publishingUpdated(...)

// Step 3: Hybrid approach (DB snapshot + event replay)
val state = userStateRepo.loadWithReplay(userId, lastSnapshotTime)

// Step 4: Full event sourcing with Pekko Persistence
val userAggregate = system.actorOf(UserAggregate.props(userId))
```

### Configuration

```scala
case class CodegenOptions(
  // ... existing options ...
  
  // Aggregate generation
  generateAggregates: Boolean = false,
  aggregatesFolder: String = "modules/core/src/main/scala/aggregates",
  aggregateRootTables: List[String] = List.empty,
  
  // Event sourcing options
  generatePekkoActors: Boolean = false,  // Generate Pekko Persistence actors
  maxAggregateDepth: Int = 3,           // Limit traversal depth
  
  // Override boundary detection
  aggregateBoundaryHints: Map[String, Boolean] = Map.empty
)
```

### CLI Usage

```bash
# Generate specific aggregate
sbt "runMain CodeGenerator aggregate users"

# Generate all configured aggregates  
sbt "runMain CodeGenerator aggregates"

# Clean aggregate files
sbt "runMain CodeGenerator reset-aggregates"
```

## Latest Features (New)

### 1. Compile-Time Event Publishing Enforcement

The generator now uses a wrapper type system that enforces event publishing at compile time:

```scala
// Generated code uses EventRequiredResult that forces event publishing
def upsert(user: User): EventRequiredResult[User] = {
  write(user) {
    ctx.run(/* database operation */).runToFuture
  }
}

// Must call one of the publishing methods to get the Future[User]
userRepo.upsert(user)
  .publishingCreated((u, cid, t) => UserCreated(u, u.id, cid, t))
```

**Benefits:**
- **Compile-time safety** - Cannot forget to publish events
- **Clean separation** - Read operations don't require events
- **Chainable API** - Monitoring and events compose cleanly
- **Type safety** - Wrong event types caught at compile time

### 2. Read/Write Operation Separation

Operations are now clearly separated by intent:

```scala
// Read operations - no events required
def getById(id: UserId): Future[Option[User]] = monitored("get-by-id") {
  read {
    ctx.run(query[User].filter(_.id == lift(id))).runToFuture.map(_.headOption)
  }
}

// Write operations - events enforced
def upsert(user: User): EventRequiredResult[User] = monitored("upsert") {
  write(user) {
    ctx.run(/* database operation */).runToFuture
  }
}
```

### 3. Consistent Return Types

All CRUD operations now consistently return full models instead of mixed ID/model returns:

- `upsert(model): EventRequiredResult[Model]` - Returns full model
- `updateXById(id, fields): EventRequiredResult[Model]` - Returns full updated model  
- `updateXByY(keyFields, updateFields): EventRequiredResult[Model]` - Returns full updated model
- `getByX(fields): Future[Option[Model]]` - Returns full model or None

### 4. Enhanced xmax Upsert Pattern

Uses PostgreSQL's `xmax = 0` system column technique for efficient insert/update detection:

```scala
// Single query determines insert vs update without separate SELECT
ctx.run(
  query[User]
    .insert(lift(user))
    .onConflictUpdate(_.id)((t, e) => t -> e) 
    .returning(r => (r, infix"xmax = 0".as[Boolean]))
).runToFuture.flatMap { case (result, wasInserted) =>
  val timestamp = TimeUtil.nowTimestamp()
  if (wasInserted) {
    writeWithTimestamp(result, timestamp)(Future.successful(()))
      .publishingCreated((u, cid, t) => UserCreated(u, u.id, cid, t))
  } else {
    writeWithTimestamp(result, timestamp)(Future.successful(()))
      .publishingUpdated((u, prev, cid, t) => UserUpdated(u, prev, u.id, cid, t))
  }
}
```

**Performance Benefits:**
- Single database roundtrip for upsert + event detection
- No separate SELECT query needed
- Leverages PostgreSQL internal metadata

### 5. EventPublishing Helper System

New `RepositoryHelpers` trait provides clean abstractions:

```scala
trait RepositoryHelpers {
  protected def read[T](dbOp: => Future[T]): Future[T] = dbOp
  
  protected def write[T](model: T)(dbOp: => Future[_]): EventRequiredResult[T] = 
    EventRequiredResult(dbOp, model, TimeUtil.nowTimestamp())
    
  protected def writeWithTimestamp[T](model: T, timestamp: Timestamp)(dbOp: => Future[_]): EventRequiredResult[T] = 
    EventRequiredResult(dbOp, model, timestamp)
}
```

**EventRequiredResult Methods:**
- `.publishingCreated(eventFactory)` - For insert operations
- `.publishingUpdated(eventFactory, previous?)` - For update operations  
- `.publishingDeleted(eventFactory)` - For delete operations
- `.publishingCustom(event)` - For custom events
- `.withoutEvent()` - Escape hatch (use sparingly)

## Recent Changes

- **Akka → Pekko Migration** - All imports updated
- **Java 21 Support** - Updated for Java 21
- **Enhanced Generation** - Handles tables without ID/timestamps
- **Event System** - Added ES-compatible events
- **AppRepositories** - Added customizable repositories
- **Configurable Paths** - Repositories can be placed anywhere
- **NEW: Compile-time enforcement** - EventRequiredResult wrapper ensures events are published
- **NEW: Read/write separation** - Clean distinction between read and write operations
- **NEW: Consistent return types** - All operations return full models
- **NEW: Efficient xmax upserts** - Single-query insert/update detection
- **NEW: Enhanced monitoring chain** - Separates monitoring from event publishing concerns

## Support

For issues or questions:
- Report bugs at: https://github.com/anthropics/claude-code/issues
- Check existing eSCALAtor documentation
- Review generated code for patterns