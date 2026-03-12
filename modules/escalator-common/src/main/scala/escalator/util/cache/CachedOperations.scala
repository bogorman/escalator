package escalator.util.cache

import scala.concurrent.{ExecutionContext, Future}

/**
 * Generic cache-through mixin for generated table classes.
 *
 * Provides cached()/cachedList()/cachingResult()/cachingResults() wrappers
 * that transparently check the cache before hitting DB. Mix in with one line
 * per generated table, like RepositoryHelpers.
 *
 * When tableCacheOpt is None (NullTableCacheProvider), all wrappers are
 * pass-through — same behavior as uncached code.
 */
trait CachedOperations[T] {

  protected def tableCacheOpt: Option[TableCache[T]]

  /** ID-based single lookup: check cache first, DB on miss, cache the result */
  protected def cached(id: Long)(action: => Future[Option[T]])(implicit ec: ExecutionContext): Future[Option[T]] =
    tableCacheOpt match {
      case Some(c) => c.getIfPresent(id) match {
        case Some(v) => Future.successful(Some(v))
        case None    => action.map { opt => opt.foreach(o => c.put(id, o)); opt }
      }
      case None => action
    }

  /** Batch ID lookup: split cached/missing, DB only for misses, merge */
  protected def cachedList(ids: List[Long])(loadMissing: List[Long] => Future[List[T]])(implicit ec: ExecutionContext): Future[List[T]] =
    tableCacheOpt match {
      case Some(c) =>
        val (hits, missingIds) = c.getPresent(ids)
        if (missingIds.isEmpty) Future.successful(hits)
        else loadMissing(missingIds).map { loaded =>
          loaded.foreach(o => c.put(c.getId(o), o))
          hits ++ loaded
        }
      case None => loadMissing(ids)
    }

  /** Query-based single result: always hits DB, caches result for future ID lookups */
  protected def cachingResult(action: => Future[Option[T]])(implicit ec: ExecutionContext): Future[Option[T]] =
    tableCacheOpt match {
      case Some(c) => action.map { opt => opt.foreach(o => c.put(c.getId(o), o)); opt }
      case None    => action
    }

  /** Query-based list result: always hits DB, caches each result for future ID lookups */
  protected def cachingResults(action: => Future[List[T]])(implicit ec: ExecutionContext): Future[List[T]] =
    tableCacheOpt match {
      case Some(c) => action.map { list => list.foreach(o => c.put(c.getId(o), o)); list }
      case None    => action
    }
}
