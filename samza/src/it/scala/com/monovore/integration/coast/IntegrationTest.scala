package com.monovore.integration.coast

import java.nio.ByteBuffer
import java.util.Properties

import com.monovore.coast
import com.monovore.coast.flow.Topic
import com.monovore.coast.model.Graph
import coast.samza.{SimpleBackend, SafeBackend, ConfigGenerator}
import com.monovore.coast.wire.{Partitioner, BinaryFormat}
import kafka.api.{FetchRequest, OffsetRequest, PartitionFetchInfo, TopicMetadataRequest}
import kafka.common.TopicAndPartition
import kafka.consumer.{ConsumerConfig, SimpleConsumer}
import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.utils.{TestUtils, TestZKUtils}
import kafka.zk.EmbeddedZookeeper
import org.apache.samza.job.ApplicationStatus
import org.apache.samza.job.local.ThreadJobFactory

import scala.util.Random

object IntegrationTest {

  def withKafkaCluster[A](withProps: java.util.Properties => A): A = {

    val Seq(port0) = TestUtils.choosePorts(1)

    val broker0 = TestUtils.createBrokerConfig(0, port0)
    broker0.setProperty("auto.create.topics.enable", "true")
    broker0.setProperty("num.partitions", "3")

    val config = new java.util.Properties()

    val brokers = s"localhost:$port0"
    val zkString = TestZKUtils.zookeeperConnect

    config.setProperty("metadata.broker.list", brokers)
    config.setProperty("producer.type", "sync")
    config.setProperty("request.required.acks", "1")
    config.setProperty("message.send.max.retries", "0")

    config.setProperty("zookeeper.connect", zkString)
    config.setProperty("group.id", "input-producer")
    config.setProperty("auto.offset.reset", "smallest")

    var zookeeper: EmbeddedZookeeper = null
    var server0: KafkaServer = null

    try {
      zookeeper = new EmbeddedZookeeper(zkString)

      server0 = TestUtils.createServer(new KafkaConfig(broker0))

      withProps(config)

    } finally {

      if (server0 != null) {
        server0.shutdown()
        server0.awaitShutdown()
      }

      if (zookeeper != null) {
        zookeeper.shutdown()
      }
    }
  }

  def fuzz(graph: Graph, input: Messages, simple: Boolean = false): Messages = {

    val factory = new ThreadJobFactory

    var producer: Producer[Array[Byte], Array[Byte]] = null
    var consumer: SimpleConsumer = null

    IntegrationTest.withKafkaCluster { config =>

      val producerConfig = new ProducerConfig(config)

      try {

        IntegrationTest.expect(input.messages.keySet, config)

        val port = config.getProperty("metadata.broker.list")
          .split(",")(0)
          .split(":")(1)
          .toInt

        producer = new Producer(producerConfig)
        consumer = new SimpleConsumer("localhost", port, ConsumerConfig.SocketTimeout, ConsumerConfig.SocketBufferSize, ConsumerConfig.DefaultClientId)

        for {
          (name, messages) <- input.messages
          numPartitions = {
            val meta = consumer.send(new TopicMetadataRequest(Seq(name), 913))
            meta.topicsMetadata.find { _.topic == name }.get
              .partitionsMetadata.size
          }
          (key, (partitioner, values)) <- messages
          partitionId = partitioner(numPartitions)
          value <- values.grouped(100)
        } {
          producer.send(value.map { value => new KeyedMessage(name, key.toArray, partitionId, value.toArray)}: _*)
        }

        val baseConfig = coast.samza.config(
          // toy-problem config
          "task.commit.ms" -> "300",

          "task.checkpoint.factory" -> "org.apache.samza.checkpoint.kafka.KafkaCheckpointManagerFactory",
          "task.checkpoint.system" -> "coast-system",
          "task.checkpoint.replication.factor" -> "1",
          // overridden in safe config generator
          "systems.coast-system.samza.factory" -> "org.apache.samza.system.kafka.KafkaSystemFactory",
          // point things at local kafka / zookeeper2
          "systems.coast-system.consumer.zookeeper.connect" -> config.getProperty("zookeeper.connect"),
          "systems.coast-system.producer.metadata.broker.list" -> config.getProperty("metadata.broker.list")
        )

        val backend = if (simple) SimpleBackend else SafeBackend

        val configs = backend(baseConfig).configure(graph)

        // FLAIL!

        val sleeps =
          if (simple) Seq(8000)
          else (0 until 4).map { _ => Random.nextInt(800) + 600} ++ Seq(8000)

        for (sleepTime <- sleeps) {

          val jobs = configs.values.toSeq
            .map { config => factory.getJob(config)}

          jobs.foreach {
            _.submit()
          }

          Thread.sleep(sleepTime)

          jobs.foreach {
            _.kill()
          }

          jobs.foreach { job =>
            job.waitForFinish(2000) match {
              case ApplicationStatus.SuccessfulFinish => ()
              case ApplicationStatus.UnsuccessfulFinish => ()
              case _ => sys.error("Taking a very long time!")
            }
          }
        }

        val outputStreams = graph.bindings.map { case (name, _) => name}

        IntegrationTest.slurp(outputStreams.toSet, config)

      } finally {

        if (producer != null) {
          producer.close()
        }

        if (consumer != null) {
          consumer.close()
        }
      }
    }
  }

  def expect(topics: Set[String], config: Properties): Unit = {
    slurp(topics, config)
    Thread.sleep(300)
  }

  def slurp(topics: Set[String], config: Properties): Messages = {

    var simple: Map[Int, SimpleConsumer] = null

    try {

      val ports = config.getProperty("metadata.broker.list").split(",")
        .map { _.split(":")(1).toInt }

      simple = ports
        .map { port =>
          port -> new SimpleConsumer("localhost", port, ConsumerConfig.SocketTimeout, ConsumerConfig.SocketBufferSize, ConsumerConfig.DefaultClientId)
        }
        .toMap

      val meta = simple.values.head.send(new TopicMetadataRequest(topics.toSeq, 236))

      def toByteSeq(bb: ByteBuffer): Seq[Byte] = {
        val bytes = Array.ofDim[Byte](bb.remaining())
        bb.duplicate().get(bytes)
        bytes.toSeq
      }

      val outputMessages = meta.topicsMetadata
        .map { topic =>
          val messages = topic.partitionsMetadata
            .flatMap { partition =>
              val broker = partition.leader.get.port

              val consumer = simple(broker)

              val tp = TopicAndPartition(topic.topic, partition.partitionId)

              val offset = consumer.earliestOrLatestOffset(tp, OffsetRequest.LatestTime, 153)

              val response = consumer.fetch(new FetchRequest(
                correlationId = Random.nextInt(),
                clientId = ConsumerConfig.DefaultClientId,
                maxWait = ConsumerConfig.MaxFetchWaitMs,
                minBytes = 0,
                requestInfo = Map(
                  tp -> PartitionFetchInfo(0L, Int.MaxValue)
                )
              ))

              response.data(tp).messages.toSeq
                .map { mao => toByteSeq(mao.message.key) -> (partition.partitionId, toByteSeq(mao.message.payload)) }
            }

          topic.topic -> messages.groupBy { _._1 }
            .mapValues { p =>
              val (_, pairs) = p.unzip
              val (partitions, data) = pairs.unzip
              ({n: Int => partitions.head }, data)
            }
        }
        .toMap

      Messages(outputMessages)

    } finally {

      if (simple != null) {
        simple.values.foreach { _.close() }
      }
    }
  }
}

case class Messages(messages: Map[String, Map[Seq[Byte], (Int => Int, Seq[Seq[Byte]])]] = Map.empty) {

  def add[A : BinaryFormat : Partitioner, B : BinaryFormat](name: Topic[A,B], messages: Map[A, Seq[B]]): Messages = {

    val formatted = messages.map { case (k, vs) =>
      val pn: (Int => Int) = implicitly[Partitioner[A]].partition(k, _)
      BinaryFormat.write(k).toSeq -> (pn, vs.map { v => BinaryFormat.write(v).toSeq })
    }

    Messages(this.messages.updated(name.name, formatted))
  }

  def get[A : BinaryFormat, B : BinaryFormat](name: Topic[A, B]): Map[A, Seq[B]] = {

    val data = messages.getOrElse(name.name, Map.empty)

    data.map { case (k, (_, vs) ) =>
      BinaryFormat.read[A](k.toArray) -> vs.map { v => BinaryFormat.read[B](v.toArray) }
    }.withDefaultValue(Seq.empty[B])
  }
}

object Messages extends Messages(Map.empty)