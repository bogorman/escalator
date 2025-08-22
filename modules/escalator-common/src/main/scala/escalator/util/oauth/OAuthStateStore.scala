package escalator.util.oauth

import scala.collection.concurrent.TrieMap
case class PendingState(provider: String, verifier: Option[String])

object OAuthStateStore {
  private val store = new TrieMap[String, PendingState]()
  def put(state: String, ps: PendingState): Unit = store.put(state, ps)
  def get(state: String): Option[PendingState]    = store.remove(state)
}