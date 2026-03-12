package escalator.util.cache

/**
 * Minimal cache interface for generated table code.
 *
 * Synchronous methods only — no DB calls. The generated CachedOperations[T]
 * mixin handles the DB fallback via cached()/cachedList() wrappers.
 */
trait TableCache[T] {
  def getIfPresent(id: Long): Option[T]
  def getPresent(ids: List[Long]): (List[T], List[Long])
  def put(id: Long, value: T): Unit
  def getId(obj: T): Long
}
