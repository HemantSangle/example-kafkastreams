// Copyright (C) 2011-2012 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package example

import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.{StreamsBuilder, Topology}
import org.apache.kafka.streams.kstream.{JoinWindows, KStream}
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.state.{KeyValueStore, Stores}

object LeftJoinAggregateExample extends LazyLogging with Kafka {

  import Kafka._

  private val ThisTopic = "this"
  private val OtherTopic = "other"
  private val OutputTopic = "output"

  def main(args: Array[String]): Unit = {
    kafkaStart()

    daemonThread {
      kafkaProduce { producer =>
        thisAndOther(producer)
      }
    }

    daemonThread {
      startStreams(join())
    }

    daemonThread {
      kafkaConsume(OutputTopic) { record =>
        logger.info(s"${record.key}: ${record.value}")
      }
    }

    readLine()

    kafkaStop()
  }

  def thisAndOther(producer: KafkaProducer[K, V]): Unit = {
    for {
      i <- 0 to 999
    } {
      val key = "%03d".format(i)
      producer.send(ThisTopic, key, key)
      for (j <- 0 to 5) {
        Thread.sleep(200L)
        producer.send(OtherTopic, key, s"$j")
      }
      Thread.sleep(5000L)
    }
  }

  def join(): Topology = {
    val AggregationStoreName = "aggregation-store"
    Thread.sleep(5000L)

    val builder = new StreamsBuilder()

    val thisStream: KStream[K, V] = builder.stream[K, V](ThisTopic)
    val otherStream: KStream[K, V] = builder.stream[K, V](OtherTopic)

    val joinedStream: KStream[K, V] = thisStream.sjoin(
      otherStream,
      JoinWindows.of(10000L).until(10 * 10000L)
    ) { (t: String, o: String) =>
      s"$t->$o"
    }

    builder.addStateStore(
      Stores.keyValueStoreBuilder(
        Stores.persistentKeyValueStore(AggregationStoreName),
        Serdes.String(),
        Serdes.String()
      )
    )

    val aggregatedStream: KStream[K, V] = joinedStream.stransform(
      AggregationStoreName
    ) {
      new Aggregator(AggregationStoreName)
    }

    aggregatedStream.to(OutputTopic)

    builder.build()
  }

  /*
   * Poor man aggregation based on punctuation without any guarantees.
   */
  class Aggregator(storeName: String) extends AbstractTransformer {

    import collection.JavaConverters._

    lazy val store = context.getStateStore(storeName).asInstanceOf[KeyValueStore[K, V]]

    override def doInit(): Unit = {

      def p(timestamp: Long) = {
        store.all().asScala.foreach { kv =>
          context.forward(kv.key, kv.value)
          store.delete(kv.key)
        }
        context.commit()
      }

      context.schedule(10000L, PunctuationType.WALL_CLOCK_TIME, (timestamp: Long) => p(timestamp))
    }

    override def doTransform(key: K, value: V): Unit = {
      Option(store.get(key)).fold(store.put(key, value)) { oldValue =>
        store.put(key, s"$oldValue, $value")
      }
    }
  }

}
