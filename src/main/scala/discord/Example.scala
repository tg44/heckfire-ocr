package discord

import scala.language.higherKinds
import scala.util.{Failure, Success}
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.stream.{ActorMaterializer, Materializer}
import cats.{Functor, Id, Monad}
import img.ImagePipeline
import net.katsstuff.ackcord.commands._
import net.katsstuff.ackcord.data.{ChannelId, GuildId}
import net.katsstuff.ackcord.http.requests.{BotAuthentication, RequestHelper}
import net.katsstuff.ackcord.http.rest.CreateMessage
import net.katsstuff.ackcord.util.{GuildRouter, Streamable}
import net.katsstuff.ackcord.websocket.gateway.GatewaySettings
import net.katsstuff.ackcord.{APIMessage, Cache, DiscordShard, MemoryCacheSnapshot}

import scala.concurrent.{ExecutionContext, Future}


class Example(settings: GatewaySettings, cache: Cache, shard: ActorRef, channelConfig: Option[String], flow: Flow[((String,String),ChannelId), (String, ChannelId), NotUsed]) extends Actor with ActorLogging {
  import cache.mat
  implicit val system: ActorSystem = context.system

  val requests: RequestHelper = RequestHelper.create(BotAuthentication(settings.token))

  cache.subscribeAPI.via(Example.withChannelInfoApiMessage)
    .collect{case (msg, Some(channelId)) => (msg, channelId)}
    .collect{case (msg : APIMessage.MessageMessage, chId) if msg.message.attachment.nonEmpty => (msg.message.attachment, chId)}
    .filter{case (msg, channelId) =>
      val decision = channelConfig.forall(s => ChannelId(s.toLong) == channelId)
      if(decision){log.info(s"$channelId processing started")} else {log.info(s"$channelId filtered out")}
      decision
    }
    .mapConcat{case (atts, chId) => atts.map(a => (a.filename -> a.url) -> chId).toList}
    .via(flow)
    .mapAsync(3){ case (resp, chId) =>
      requests.singleFuture(CreateMessage.mkContent(chId, resp))
    }
    .to(Sink.ignore).run()

  shard ! DiscordShard.StartShard

  override def receive: Receive = {
    case DiscordShard.StopShard =>
      shard ! DiscordShard.StopShard
  }



}
object Example {
  def props(settings: GatewaySettings, cache: Cache, shard: ActorRef, channelId: Option[String])(implicit actorSystem: ActorSystem, materializer: Materializer, executionContext: ExecutionContext): Props =
    Props(new Example(settings, cache, shard, channelId, new ImagePipeline().stream))

  def withChannelInfoApiMessage[Msg <: APIMessage]: Flow[Msg, (Msg, Option[ChannelId]), NotUsed] =
    Flow[Msg].map { msg =>
      val optGuildId = msg match {
        case _ @(_: APIMessage.Ready | _: APIMessage.Resumed | _: APIMessage.UserUpdate) =>
          None
        case msg: APIMessage.GuildMessage =>
          None
        case msg: APIMessage.ChannelMessage =>
          None
        case msg: APIMessage.MessageMessage =>
          Option(msg.message.channelId)
        case APIMessage.VoiceStateUpdate(state, _) => None
      }

      msg -> optGuildId
    }
}