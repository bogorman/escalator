package escalator.db.generators

import java.sql.Connection

/**
 * Shared utility for building reference trees
 * Used by both AggregateGenerator and ActorGenerator
 */
object ReferenceTreeBuilder {

  /**
   * Build a reference tree starting from a root table, traversing foreign key relationships
   * up to a maximum depth.
   *
   * @param db Database connection
   * @param rootTableName The root table to start from
   * @param maxDepth Maximum depth to traverse
   * @param options CodegenOptions containing aggregate root hints
   * @return ReferenceTree representing the aggregate hierarchy
   */
  def buildReferenceTree(
      db: Connection,
      rootTableName: String,
      maxDepth: Int,
      options: CodegenOptions
  ): ReferenceTree = {

    def isLikelyAggregateRoot(tableName: String): Boolean = {
      val rootPatterns = options.aggregateRootTables
      rootPatterns.contains(tableName.toLowerCase) ||
      options.aggregateBoundaryHints.getOrElse(tableName, false)
    }

    def traverse(tableName: String, depth: Int, visited: Set[String]): List[ReferenceNode] = {
      if (depth >= maxDepth || visited.contains(tableName)) {
        return List.empty
      }

      val refs = ConnectionUtils.getReferences(db, tableName, "id")

      refs.flatMap { ref =>
        // Stop traversal at other aggregate boundaries (unless it's depth 0, i.e., the root)
        if (depth > 0 && isLikelyAggregateRoot(ref.fromTableName)) {
          // Just store the ID reference, don't traverse deeper
          Some(ReferenceNode(
            table = ref.fromTableName,
            foreignKeyColumn = ref.fromColName,
            referencedTable = Some(ref.toTableName),
            referencedColumn = Some(ref.toColumnName),
            children = List.empty,
            isWeakReference = true,
            depth = depth
          ))
        } else {
          // Continue traversing within this aggregate
          Some(ReferenceNode(
            table = ref.fromTableName,
            foreignKeyColumn = ref.fromColName,
            referencedTable = Some(ref.toTableName),
            referencedColumn = Some(ref.toColumnName),
            children = traverse(ref.fromTableName, depth + 1, visited + tableName),
            isWeakReference = false,
            depth = depth
          ))
        }
      }
    }

    ReferenceTree(rootTableName, traverse(rootTableName, 0, Set.empty))
  }
}
