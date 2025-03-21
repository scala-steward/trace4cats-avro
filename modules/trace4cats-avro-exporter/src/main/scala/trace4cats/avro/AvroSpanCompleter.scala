package trace4cats.avro

import cats.effect.kernel.Async
import cats.effect.kernel.Resource

import fs2.Chunk
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import trace4cats.CompleterConfig
import trace4cats.QueuedSpanCompleter
import trace4cats.kernel.SpanCompleter
import trace4cats.model.TraceProcess

object AvroSpanCompleter {

  def udp[F[_]: Async](
      process: TraceProcess,
      host: String = agentHostname,
      port: Int = agentPort,
      config: CompleterConfig = CompleterConfig()
  ): Resource[F, SpanCompleter[F]] =
    Resource.eval(Slf4jLogger.create[F]).flatMap { implicit logger: Logger[F] =>
      AvroSpanExporter.udp[F, Chunk](host, port).flatMap(QueuedSpanCompleter[F](process, _, config))
    }

  def tcp[F[_]: Async](
      process: TraceProcess,
      host: String = agentHostname,
      port: Int = agentPort,
      config: CompleterConfig = CompleterConfig()
  ): Resource[F, SpanCompleter[F]] = {
    Resource.eval(Slf4jLogger.create[F]).flatMap { implicit logger: Logger[F] =>
      AvroSpanExporter.tcp[F, Chunk](host, port).flatMap(QueuedSpanCompleter[F](process, _, config))
    }
  }

}
