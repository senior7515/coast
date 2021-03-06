package com.monovore.coast.samza

import com.monovore.coast.model.Sink
import com.monovore.coast.samza
import com.monovore.coast.wire.BinaryFormat
import org.apache.samza.config.{Config, MapConfig}
import org.apache.samza.task.TaskContext

object SafeBackend extends SamzaBackend {

  def apply(baseConfig: Config = new MapConfig()): ConfigGenerator = new SafeConfigGenerator(baseConfig)

  class SinkFactory[A, B](sinkNode: Sink[A, B]) extends CoastTask.Factory {

    override def make(config: Config, context: TaskContext, whatSink: CoastTask.Receiver): CoastTask.Receiver = {

      val streamName = config.get(samza.TaskName)

      val partitionIndex = context.getTaskName.getTaskName.split("\\W+").last.toInt // ICK!

      val regroupedStreams = config.get(RegroupedStreams).split(",")
        .filter { _.nonEmpty }
        .toSet

      val partitions = SamzaBackend.getPartitions(config, CoastSystem, streamName)

      val offsetThreshold =
        if (regroupedStreams(streamName)) 0L
        else partitions(partitionIndex)

      val finalSink = new MessageSink.ByteSink {

        override def execute(stream: String, partition: Int, offset: Long, key: Array[Byte], value: Array[Byte]): Long = {

          val payload =
            if (regroupedStreams(streamName)) {
              BinaryFormat.write(FullMessage(streamName, partitionIndex, offset, value))
            }
            else value

          if (offset >= offsetThreshold) {
            whatSink.send(streamName, partition, offset, key, payload)
          }

          offset + 1
        }
      }

      val compiler = new TaskCompiler(new TaskCompiler.Context {
        override def getStore[P, A, B](path: String, default: B): CoastState[Int, A, B] =
          context.getStore(path).asInstanceOf[CoastStorageEngine[A, B]].withDefault(default)
      })

      val compiled = compiler.compileSink(sinkNode, finalSink, streamName, partitions.size)

      val mergeStream = s"coast.merge.$streamName"

      new CoastTask.Receiver {

        override def send(stream: String, partition: Int, offset: Long, key: Array[Byte], value: Array[Byte]) {

          if (stream == mergeStream) {

            val fullMessage = BinaryFormat.read[FullMessage](value)

            compiled.execute(fullMessage.stream, partition, fullMessage.offset, key, fullMessage.value)

          } else {

            // Ensure the message is framed
            // If it comes from a regrouped stream, it's framed already
            val valueBytes =
              if (regroupedStreams(stream)) value
              else BinaryFormat.write(FullMessage(stream, 0, offset, value))

            whatSink.send(
              mergeStream,
              partitionIndex,
              -1,
              key,
              valueBytes
            )
          }
        }
      }
    }
  }
}

trait MessageSink[-K, -V] extends Serializable {

  def execute(stream: String, partition: Int, offset: Long, key: K, value: V): Long
}

object MessageSink {

  type ByteSink = MessageSink[Array[Byte], Array[Byte]]
}
