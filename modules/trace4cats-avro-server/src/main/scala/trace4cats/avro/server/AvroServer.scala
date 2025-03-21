package trace4cats.avro.server

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._

import com.comcast.ip4s.Port
import fs2.Chunk
import fs2.Pipe
import fs2.Pull
import fs2.Stream
import fs2.io.net.Network
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DecoderFactory
import org.typelevel.log4cats.Logger
import trace4cats.avro.AvroInstances
import trace4cats.avro.agentPort
import trace4cats.model.CompletedSpan

object AvroServer {

  private def buffer[F[_]]: Pipe[F, Byte, Chunk[Byte]] =
    bytes => {
      def go(lastBytes: (Byte, Byte), state: List[Byte], stream: Stream[F, Byte]): Pull[F, Chunk[Byte], Unit] =
        stream.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            val newLastBytes = (lastBytes._2, hd)
            val newState     = hd :: state
            if (newLastBytes === (0xc4.byteValue -> 0x02.byteValue))
              Pull.output1(Chunk(newState.drop(2).reverse: _*)) >> go((0, 0), List.empty, tl)
            else
              go(newLastBytes, newState, tl)

          case None => Pull.done
        }

      go((0, 0), List.empty, bytes).stream
    }

  private def decode[F[_]: Sync: Logger](schema: Schema)(bytes: Chunk[Byte]): F[Option[CompletedSpan]] =
    Sync[F].delay {
      val reader  = new GenericDatumReader[Any](schema)
      val decoder = DecoderFactory.get.binaryDecoder(bytes.toArray, null) // scalafix:ok
      val record  = reader.read(null, decoder)                            // scalafix:ok

      record
    }
      .flatMap[Option[CompletedSpan]] { record =>
        Sync[F].fromEither(AvroInstances.completedSpanCodec.decode(record, schema).bimap(_.throwable, Some(_)))
      }
      .handleErrorWith(th => Logger[F].warn(th)("Failed to decode span batch").as(Option.empty[CompletedSpan]))

  def tcp[F[_]: Async: Logger](
      sink: Pipe[F, CompletedSpan, Unit],
      port: Int = agentPort
  ): Resource[F, Stream[F, Unit]] =
    for {
      avroSchema  <- Resource.eval(AvroInstances.completedSpanSchema[F])
      socketGroup <- Network[F].socketGroup()
      port        <- Resource.eval(Port.fromInt(port).liftTo[F](new IllegalArgumentException(s"invalid port $port")))
    } yield socketGroup
      .server(port = Some(port))
      .map { socket =>
        socket.reads
          .through(buffer[F])
          .evalMap(decode[F](avroSchema))
          .unNone
          .through(sink)
      }
      .parJoin(100)

  def udp[F[_]: Async: Logger](
      sink: Pipe[F, CompletedSpan, Unit],
      port: Int = agentPort
  ): Resource[F, Stream[F, Unit]] =
    for {
      avroSchema  <- Resource.eval(AvroInstances.completedSpanSchema[F])
      port        <- Resource.eval(Port.fromInt(port).liftTo[F](new IllegalArgumentException(s"invalid port $port")))
      socketGroup <- Network[F].datagramSocketGroup()
      socket      <- socketGroup.openDatagramSocket(port = Some(port))
    } yield socket.reads
      .map(_.bytes)
      .evalMap(decode[F](avroSchema))
      .unNone
      .through(sink)

}
