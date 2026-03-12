package escalator.util.cache

/**
 * Resolves a TableCache[T] for a given table name.
 *
 * Set once at app startup via TableCacheProvider.set(). Generated tables
 * resolve their cache from the global instance via lazy val.
 * If not set, NullTableCacheProvider is used (no caching).
 */
trait TableCacheProvider {
  def forTable[T](tableName: String): Option[TableCache[T]]
}

object NullTableCacheProvider extends TableCacheProvider {
  def forTable[T](tableName: String): Option[TableCache[T]] = None
}

object TableCacheProvider {
  @volatile private var _instance: TableCacheProvider = NullTableCacheProvider

  def set(provider: TableCacheProvider): Unit = { _instance = provider }
  def instance: TableCacheProvider = _instance
}
