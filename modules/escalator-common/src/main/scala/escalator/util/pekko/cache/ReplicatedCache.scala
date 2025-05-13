package sample.distributeddata

import java.nio.charset.StandardCharsets

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import org.apache.pekko.cluster.ddata.{DistributedData, LWWMap, LWWMapKey, SelfUniqueAddress}
import org.apache.pekko.cluster.ddata.Replicator.{Get, GetResponse, GetSuccess, NotFound, ReadLocal, Update, UpdateResponse, WriteLocal}
import scala.collection.mutable
import scala.concurrent.duration._

object ReplicatedCache {
  // === External Commands & Replies ===
  // Note: These messages no longer extend any marker trait because they are not persisted.
  sealed trait Command

  // Single key operations:
  // The payload is an Array[Byte] containing the Kryo-serialized bytes.
  final case class PutInCache(key: String, payload: Array[Byte]) extends Command
  final case class GetFromCache(key: String, replyTo: ActorRef) extends Command
  final case class Cached(key: String, payload: Option[Array[Byte]]) extends Command
  final case class Evict(key: String) extends Command

  // Bulk operations:
  final case class PutBulk(entries: Map[String, Array[Byte]]) extends Command
  final case class GetBulk(keys: Set[String], replyTo: ActorRef) extends Command
  final case class BulkCached(results: Map[String, Option[Array[Byte]]]) extends Command

  // Internal messages:
  // Trigger periodic cleanup of the local cache.
  private case object Cleanup extends Command
  // Trigger periodic printing of cache statistics.
  private case object PrintStats extends Command

  /**
   * Props for the ReplicatedCache actor.
   *
   * @param ttl              Time-to-live for local cache entries (default 10 seconds)
   * @param cleanupInterval  How often to perform periodic cleanup (default 5 seconds)
   * @param statInterval     How often to print cache statistics (default 30 seconds)
   * @param maxLocalMemory   Maximum estimated memory usage for the local cache in bytes (default 1GB)
   */
  def props(
      ttl: FiniteDuration = 10.seconds,
      cleanupInterval: FiniteDuration = 5.seconds,
      statInterval: FiniteDuration = 30.seconds,
      maxLocalMemory: Long = 1024L * 1024L * 1024L  // 1GB
  ): Props = Props(new ReplicatedCache(ttl, cleanupInterval, statInterval, maxLocalMemory))
}

/**
 * A classic actor that uses Distributed Data with durable storage while maintaining
 * a local in‑RAM cache for frequently accessed keys.
 *
 * In this version, values are stored as binary payloads (Array[Byte]) that have been serialized
 * using Kryo. The local cache evicts entries that have not been accessed within TTL or if the total
 * estimated memory usage exceeds maxLocalMemory (default 1GB), in which case the oldest entries are removed.
 * Bulk operations for put and get are supported. Periodically, the actor prints statistics about the
 * number of keys and estimated memory usage.
 */
class ReplicatedCache(
    ttl: FiniteDuration,
    cleanupInterval: FiniteDuration,
    statInterval: FiniteDuration,
    maxLocalMemory: Long
) extends Actor with ActorLogging {
  import ReplicatedCache._
  import context.dispatcher  // for scheduler

  // Distributed Data replicator.
  val replicator: ActorRef = DistributedData(context.system).replicator
  // Unique address of this node (needed for removals).
  implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress

  // Use a single durable key for the entire cache.
  // IMPORTANT: Ensure that "cache" is listed in durable.keys in your configuration.
  val DataKey: LWWMapKey[String, Array[Byte]] = LWWMapKey("cache")

  /**
   * Local in-memory cache.
   * Each entry is stored as: key -> (payload, lastAccessTimestamp, estimatedSize)
   */
  private val localCache: mutable.Map[String, (Array[Byte], Long, Long)] = mutable.Map.empty
  // Track the total estimated memory usage of the local cache.
  private var currentLocalMemoryUsage: Long = 0L
  // Fixed overhead (in bytes) per cache entry, used for estimating memory usage.
  private val perEntryOverhead: Long = 64L

  // Helper: compute the estimated memory usage for a given key/payload.
  private def estimateEntrySize(key: String, payload: Array[Byte]): Long = {
    val keySize = key.getBytes(StandardCharsets.UTF_8).length
    val payloadSize = payload.length
    keySize + payloadSize + perEntryOverhead
  }

  // Evict oldest entries until currentLocalMemoryUsage is within maxLocalMemory.
  private def updateMemoryUsageAndEvictIfNeeded(): Unit = {
    while (currentLocalMemoryUsage > maxLocalMemory && localCache.nonEmpty) {
      // Find the entry with the oldest lastAccess timestamp.
      val oldestKey = localCache.minBy { case (_, (_, lastAccess, _)) => lastAccess }._1
      val (_, _, size) = localCache(oldestKey)
      log.info(s"Memory pressure: evicting key [$oldestKey] of estimated size [$size] bytes")
      localCache.remove(oldestKey)
      currentLocalMemoryUsage -= size
    }
  }

  // Schedule periodic cleanup (TTL) of the local cache.
  val cleanupTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(cleanupInterval, cleanupInterval, self, Cleanup)
  // Schedule periodic printing of statistics.
  val statTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(statInterval, statInterval, self, PrintStats)

  override def postStop(): Unit = {
    cleanupTask.cancel()
    statTask.cancel()
    super.postStop()
  }

  override def receive: Receive = {
    // ----- Single Key Operations -----
    case PutInCache(key, payload) =>
      val now = System.currentTimeMillis()
      val estimatedSize = estimateEntrySize(key, payload)
      localCache.get(key).foreach { case (_, _, oldSize) =>
        currentLocalMemoryUsage -= oldSize
      }
      localCache.update(key, (payload, now, estimatedSize))
      currentLocalMemoryUsage += estimatedSize
      updateMemoryUsageAndEvictIfNeeded()

      replicator ! Update(
        DataKey,
        LWWMap.empty[String, Array[Byte]],
        WriteLocal,
        request = Some(PutInCache(key, payload))
      ) { map =>
        map :+ (key -> payload)
      }

    case Evict(key) =>
      localCache.get(key).foreach { case (_, _, size) =>
        currentLocalMemoryUsage -= size
      }
      localCache.remove(key)
      replicator ! Update(
        DataKey,
        LWWMap.empty[String, Array[Byte]],
        WriteLocal,
        request = Some(Evict(key))
      ) { map =>
        map.remove(node, key)
      }

    case GetFromCache(key, replyTo) =>
      val now = System.currentTimeMillis()
      localCache.get(key) match {
        case Some((payload, _, size)) =>
          localCache.update(key, (payload, now, size))
          replyTo ! Cached(key, Some(payload))
        case None =>
          replicator ! Get(DataKey, ReadLocal, request = Some((key, replyTo)))
      }

    case g @ GetSuccess(DataKey, Some((key: String, replyTo: ActorRef))) =>
      val data = g.get(DataKey).asInstanceOf[LWWMap[String, Array[Byte]]]
      val result = data.entries.get(key)
      result.foreach { payload =>
        val now = System.currentTimeMillis()
        val estimatedSize = estimateEntrySize(key, payload)
        localCache.get(key).foreach { case (_, _, oldSize) =>
          currentLocalMemoryUsage -= oldSize
        }
        localCache.update(key, (payload, now, estimatedSize))
        currentLocalMemoryUsage += estimatedSize
        updateMemoryUsageAndEvictIfNeeded()
      }
      replyTo ! Cached(key, result)

    case NotFound(DataKey, Some((key: String, replyTo: ActorRef))) =>
      replyTo ! Cached(key, None)

    // ----- Bulk Operations -----
    case PutBulk(entries) =>
      val now = System.currentTimeMillis()
      entries.foreach { case (key, payload) =>
        val estimatedSize = estimateEntrySize(key, payload)
        localCache.get(key).foreach { case (_, _, oldSize) =>
          currentLocalMemoryUsage -= oldSize
        }
        localCache.update(key, (payload, now, estimatedSize))
        currentLocalMemoryUsage += estimatedSize
      }
      updateMemoryUsageAndEvictIfNeeded()
      replicator ! Update(
        DataKey,
        LWWMap.empty[String, Array[Byte]],
        WriteLocal,
        request = Some(PutBulk(entries))
      ) { map =>
        entries.foldLeft(map) { case (m, (key, payload)) =>
          m :+ (key -> payload)
        }
      }

    case GetBulk(keys, replyTo) =>
      val now = System.currentTimeMillis()
      val (foundKeys, missingKeys) = keys.partition(localCache.contains)
      val foundValues: Map[String, Array[Byte]] = foundKeys.map { key =>
        val (payload, _, size) = localCache(key)
        localCache.update(key, (payload, now, size))
        key -> payload
      }.toMap
      if (missingKeys.isEmpty) {
        replyTo ! BulkCached(foundValues.view.mapValues(Some(_)).toMap)
      } else {
        replicator ! Get(DataKey, ReadLocal, request = Some((missingKeys, replyTo, foundValues)))
      }

    case g @ GetSuccess(DataKey, Some(payload)) =>
      payload match {
        case (missing: Set[String], replyTo: ActorRef, found: Map[String, Array[Byte]]) =>
          val data = g.get(DataKey).asInstanceOf[LWWMap[String, Array[Byte]]]
          val now = System.currentTimeMillis()
          val missingValues: Map[String, Array[Byte]] = missing.flatMap { key =>
            data.entries.get(key).map(payload => key -> payload)
          }.toMap
          missingValues.foreach { case (k, payload) =>
            val estimatedSize = estimateEntrySize(k, payload)
            localCache.get(k).foreach { case (_, _, oldSize) =>
              currentLocalMemoryUsage -= oldSize
            }
            localCache.update(k, (payload, now, estimatedSize))
            currentLocalMemoryUsage += estimatedSize
          }
          updateMemoryUsageAndEvictIfNeeded()
          val combined: Map[String, Option[Array[Byte]]] =
            (found.view.mapValues(Some(_)).toMap ++ missing.map { key =>
              key -> missingValues.get(key)
            }).toMap
          replyTo ! BulkCached(combined)
        case _ =>
          log.warning("Unexpected payload in bulk get response: {}", payload)
      }

    case NotFound(DataKey, Some(payload)) =>
      payload match {
        case (missing: Set[String], replyTo: ActorRef, found: Map[String, Array[Byte]]) =>
          val combined: Map[String, Option[Array[Byte]]] =
            (found.view.mapValues(Some(_)).toMap ++ missing.map { key =>
              key -> None
            }).toMap
          replyTo ! BulkCached(combined)
        case _ =>
          log.warning("Unexpected payload in bulk get NotFound response: {}", payload)
      }

    // ----- Periodic Cleanup (TTL) -----
    case Cleanup =>
      val now = System.currentTimeMillis()
      val expiredKeys = localCache.collect {
        case (key, (_, lastAccess, size)) if (now - lastAccess) > ttl.toMillis => (key, size)
      }
      if (expiredKeys.nonEmpty) {
        log.info("TTL cleanup: evicting keys due to inactivity: {}",
          expiredKeys.map(_._1).mkString(", "))
        expiredKeys.foreach { case (key, size) =>
          localCache.remove(key)
          currentLocalMemoryUsage -= size
        }
      }
      updateMemoryUsageAndEvictIfNeeded()

    // ----- Periodic Statistics -----
    case PrintStats =>
      replicator ! Get(DataKey, ReadLocal, request = Some(PrintStats))

    case g @ GetSuccess(DataKey, Some(PrintStats)) =>
      val data = g.get(DataKey).asInstanceOf[LWWMap[String, Array[Byte]]]
      val durableCount = data.entries.size
      log.info(
        s"Cache Stats -- Durable store keys: $durableCount; " +
        s"Local cache keys: ${localCache.size}; " +
        s"Local memory usage (estimated): $currentLocalMemoryUsage bytes"
      )

    case NotFound(DataKey, Some(PrintStats)) =>
      log.info(
        s"Cache Stats -- Durable store keys: 0; " +
        s"Local cache keys: ${localCache.size}; " +
        s"Local memory usage (estimated): $currentLocalMemoryUsage bytes"
      )

    // ----- Other Replicator Responses -----
    case _: UpdateResponse[_] =>
      // Ignore update acknowledgments.
      ()

    case unknown =>
      log.warning("Received unknown message: {}", unknown)
  }
}
