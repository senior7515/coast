package com.monovore.coast
package samza

import com.monovore.coast.model._
import com.monovore.coast.wire.WireFormat
import org.apache.samza.config.Config
import org.apache.samza.task.TaskContext
import org.apache.samza.util.Logging

trait MessageSink[-K, -V] extends Serializable {

  def init(offset: Long): Unit // FIXME: laaaame

  def execute(stream: String, partition: Int, offset: Long, key: K, value: V): Long
}

object MessageSink {

  type Bytes = Array[Byte]
  type ByteSink = MessageSink[Bytes, Bytes]

  trait Factory extends Serializable {
    def make(config: Config, context: TaskContext, sink: ByteSink): ByteSink
  }

  class FromElement[A, B](sink: Sink[A, B]) extends Factory {

    override def make(config: Config, context: TaskContext, finalSink: ByteSink) = {

      def compileSource[A, B](source: Source[A, B], sink: MessageSink[A, B], prefix: List[String]) = {

        new MessageSink[Bytes, Bytes] with Logging {

          val store = context.getStore(formatPath(prefix)).asInstanceOf[CoastStore[Unit, Unit]]

          override def execute(stream: String, partition: Int, offset: Long, key: Bytes, value: Bytes): Long = {

            if (stream == source.source) {
              store.handle(offset, unit, unit) { (downstreamOffset, _) =>

                val a = source.keyFormat.read(key)
                val b = source.valueFormat.read(value)

                sink.execute(stream, partition, downstreamOffset, a, b) -> unit
              }
            } else offset
          }

          override def init(offset: Long): Unit = sink.init(store.downstreamOffset)
        }
      }

      def compilePure[A, B, B0](trans: PureTransform[A, B0, B], sink: MessageSink[A, B], prefix: List[String]) = {

        val transformed = new MessageSink[A, B0] {

          override def execute(stream: String, partition: Int, offset: Long, key: A, value: B0): Long = {
            val update = trans.function(key)
            val output = update(value)
            output.foldLeft(offset)(sink.execute(stream, partition, _, key, _))
          }

          override def init(offset: Long): Unit = sink.init(offset)
        }

        compile(trans.upstream, transformed, prefix)
      }

      def compileAggregate[S, A, B, B0](trans: Aggregate[S, A, B0, B], sink: MessageSink[A, B], prefix: List[String]) = {

        val transformed = new MessageSink[A, B0] with Logging {

          val store = context.getStore(samza.formatPath(prefix)).asInstanceOf[CoastStore[A, S]]

          override def execute(stream: String, partition: Int, offset: Long, key: A, value: B0): Long = {
            store.handle(offset, key, trans.init) { (downstreamOffset, state) =>

              val update = trans.transformer(key)

              val (newState, output) = update(state, value)

              val newDownstreamOffset = output.foldLeft(downstreamOffset)(sink.execute(stream, partition, _, key, _))

              newDownstreamOffset -> newState
            }
          }

          override def init(offset: Long): Unit = sink.init(store.downstreamOffset)
        }

        compile(trans.upstream, transformed, "aggregated" :: prefix)
      }

      def compileGroupBy[A, B, A0](gb: GroupBy[A, B, A0], sink: MessageSink[A, B], prefix: List[String]) = {

        val task = new MessageSink[A0, B] {

          override def execute(stream: String, partition: Int, offset: Long, key: A0, value: B): Long = {
            val newKey = gb.groupBy(key)(value)
            sink.execute(stream, partition, offset, newKey, value)
          }

          override def init(offset: Long): Unit = sink.init(offset)
        }

        compile(gb.upstream, task, prefix)
      }

      def compileMerge[A, B](merge: Merge[A, B], sink: MessageSink[A, B], prefix: List[String]) = {

        val downstreamSink = new MessageSink[A, B] with Logging {

          private[this] var maxOffset: Long = 0L

          override def execute(stream: String, partition: Int, offset: Long, key: A, value: B): Long = {
            maxOffset = math.max(maxOffset, offset)
            maxOffset = sink.execute(stream, partition, maxOffset, key, value)
            maxOffset
          }

          override def init(offset: Long): Unit = {
            maxOffset = math.max(maxOffset, offset)
            sink.init(maxOffset)
          }
        }

        val upstreamSinks = merge.upstreams
          .map { case (name, up) => compile(up, downstreamSink, name :: prefix) }

        new MessageSink[Bytes, Bytes] {

          override def execute(stream: String, partition: Int, offset: Long, key: Bytes, value: Bytes): Long = {

            upstreamSinks.foreach { s => s.execute(stream, partition, offset, key, value) }

            offset
          }

          override def init(offset: Long): Unit = upstreamSinks.foreach { _.init(offset) }
        }
      }

      def compile[A, B](ent: Node[A, B], sink: MessageSink[A, B], prefix: List[String]): ByteSink = {

        ent match {
          case source @ Source(_) => compileSource(source, sink, prefix)
          case merge @ Merge(_) => compileMerge(merge, sink, prefix)
          case trans @ Aggregate(_, _, _) => compileAggregate(trans, sink, prefix)
          case pure @ PureTransform(_, _) => compilePure(pure, sink, prefix)
          case group @ GroupBy(_, _) => compileGroupBy(group, sink, prefix)
        }
      }

      val last = new MessageSink[A, B] with Logging {

        var nextOffset: Long = _

        override def execute(stream: String, partition: Int, offset: Long, key: A, value: B): Long = {

          val keyBytes = sink.keyFormat.write(key)
          val valueBytes = sink.valueFormat.write(value)

          finalSink.execute(stream, partition, offset, keyBytes, valueBytes)
        }

        override def init(offset: Long): Unit = finalSink.init(offset)
      }

      val name = config.get(samza.TaskName)

      compile(sink.element, last, List(name))
    }
  }
}