package services

import akka.actor._
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ClusterShardingSettings
import domain.SchedulerAggregate

object SchedulerSharder {

  final case class Envelope(id: String, payload: Any)

  def apply()(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
        typeName = "SchedulerAggregate",
        entityProps = Props[SchedulerAggregate],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case Envelope(id, payload) => (id, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Envelope(id, _) => id.reverse.take(2).reverse // Last two characters of ID (it'll do for now)
  }

}