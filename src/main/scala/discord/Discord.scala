package discord

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import net.katsstuff.ackcord.http.requests.{BotAuthentication, RequestHelper}
import net.katsstuff.ackcord.websocket.gateway.GatewaySettings

import scala.util.{Failure, Success, Try}

class Discord {
  import net.katsstuff.ackcord._
  implicit val system: ActorSystem  = ActorSystem("AckCord")
  implicit val executionContext = system.dispatcher
  implicit val mat: Materializer = ActorMaterializer()
  val config: Config = ConfigFactory.load()

  val token = config.getString("discord.token")
  val channel = Try(config.getString("discord.channelId")).toOption
  println(s"Channel restriction online for: $channel")

  val cache = Cache.create

  val requests = RequestHelper.create(BotAuthentication(token))

  val gatewaySettings = GatewaySettings(token)

  DiscordShard.fetchWsGateway.map { wsUri => DiscordShard.connect(wsUri, gatewaySettings, cache, "DiscordShard")}.onComplete {
          case Success(shardActor) =>
            system.actorOf(Example.props(gatewaySettings, cache, shardActor, channel), "Main")
          case Failure(e) =>
            println("Could not connect to discord.Discord")
            throw e
        }

}
