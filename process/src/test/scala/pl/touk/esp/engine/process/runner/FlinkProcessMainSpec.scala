package pl.touk.esp.engine.process.runner

import java.net.ConnectException
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

import argonaut.PrettyParams
import com.typesafe.config.Config
import org.apache.flink.api.common.ExecutionConfig
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.scalatest.{FlatSpec, Inside, Matchers}
import pl.touk.esp.engine.api.exception.ExceptionHandlerFactory
import pl.touk.esp.engine.api.process._
import pl.touk.esp.engine.api.signal.SignalTransformer
import pl.touk.esp.engine.api.test.InvocationCollectors.ServiceInvocationCollector
import pl.touk.esp.engine.api.test.{EmptyLineSplittedTestDataParser, NewLineSplittedTestDataParser, TestDataParser}
import pl.touk.esp.engine.api._
import pl.touk.esp.engine.build.EspProcessBuilder
import pl.touk.esp.engine.flink.api.process.{FlinkCustomNodeContext, FlinkCustomStreamTransformation, FlinkSourceFactory}
import pl.touk.esp.engine.flink.api.signal.FlinkProcessSignalSender
import pl.touk.esp.engine.flink.util.exception.{VerboselyLoggingExceptionHandler, VerboselyLoggingRestartingExceptionHandler}
import pl.touk.esp.engine.flink.util.signal.KafkaSignalStreamConnector
import pl.touk.esp.engine.flink.util.source.{CollectionSource, EspDeserializationSchema}
import pl.touk.esp.engine.kafka.{EspSimpleKafkaProducer, KafkaConfig}
import pl.touk.esp.engine.marshall.ProcessMarshaller
import pl.touk.esp.engine.process.ProcessTestHelpers._
import pl.touk.esp.engine.spel

import scala.concurrent.{ExecutionContext, Future}

class FlinkProcessMainSpec extends FlatSpec with Matchers with Inside {

  import spel.Implicits._

  val ProcessMarshaller = new ProcessMarshaller

  it should "be able to compile and serialize services" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .filter("filter1", "#sum(#input.![value1]) > 24")
        .processor("proc2", "logService", "all" -> "#distinct(#input.![value2])")
        .sink("out", "monitor")

    FlinkProcessMain.main(Array(ProcessMarshaller.toJson(process, PrettyParams.spaces2)))
  }

}

object LogService extends Service {

  val invocationsCount = new AtomicInteger(0)

  def clear() = {
    invocationsCount.set(0)
  }

  @MethodToInvoke
  def invoke(@ParamName("all") all: Any)(implicit ec: ExecutionContext, collector: ServiceInvocationCollector): Future[Unit] = {
    if (collector.collectorEnabled) {
      collector.collect(s"$all-collectedDuringServiceInvocation")
      Future.successful(Unit)
    } else {
      invocationsCount.incrementAndGet()
      Future.successful(Unit)
    }
  }
}

class ThrowingService(exception: Exception) extends Service {
  def invoke(@ParamName("throw") throwing: Boolean): Future[Unit] = {
    if (throwing) {
      Future.failed(exception)
    } else  Future.successful(Unit)
  }
}


object CustomSignalReader extends CustomStreamTransformer {

  @SignalTransformer(signalClass = classOf[TestProcessSignalFactory])
  @MethodToInvoke(returnType = classOf[Void])
  def execute() =
    FlinkCustomStreamTransformation((start: DataStream[InterpretationResult], context: FlinkCustomNodeContext) => {
      context.signalSenderProvider.get[TestProcessSignalFactory]
        .connectWithSignals(start, context.metaData.id, context.nodeId, new EspDeserializationSchema(identity))
        .map((a:InterpretationResult) => ValueWithContext(a),
              (_:Array[Byte]) => ValueWithContext[Any]("", Context("id")))
  })
}


class TestProcessSignalFactory(val kafkaConfig: KafkaConfig, val signalsTopic: String)
  extends FlinkProcessSignalSender with EspSimpleKafkaProducer with KafkaSignalStreamConnector {

  @MethodToInvoke
  def sendSignal()(processId: String) = {
    sendToKafkaWithNewProducer(signalsTopic, Array.empty, "".getBytes())
  }

}



class SimpleProcessConfigCreator extends ProcessConfigCreator {

  import org.apache.flink.streaming.api.scala._

  override def services(config: Config) = Map(
    "logService" -> WithCategories(LogService, "c1"),
    "throwingService" -> WithCategories(new ThrowingService(new RuntimeException("Thrown as expected")), "c1"),
    "throwingTransientService" -> WithCategories(new ThrowingService(new ConnectException()), "c1")

  )

  override def sinkFactories(config: Config) = Map(
    "monitor" -> WithCategories(new SinkFactory { def create(): Sink = MonitorEmptySink}, "c2"),
    "sinkForInts" -> WithCategories(SinkFactory.noParam(SinkForInts))
  )

  override def listeners(config: Config) = List()

  override def customStreamTransformers(config: Config) = Map("stateCustom" -> WithCategories(StateCustomNode),
          "signalReader" -> WithCategories(CustomSignalReader)
  )

  override def sourceFactories(config: Config) = Map(
    "input" -> WithCategories(TestSources.simpleRecordSource, "cat2"),
    "jsonInput" -> WithCategories(TestSources.jsonSource, "cat2")
  )

  override def signals(config: Config) = Map("sig1" ->
          WithCategories(new TestProcessSignalFactory(KafkaConfig("", "", None, None), "")))


  override def exceptionHandlerFactory(config: Config) =
    ExceptionHandlerFactory.noParams(VerboselyLoggingRestartingExceptionHandler)

  override def globalProcessVariables(config: Config): Map[String, WithCategories[Class[_]]] = Map.empty

  override def buildInfo(): Map[String, String] = Map.empty
}

object TestSources {
  import org.apache.flink.streaming.api.scala._

  import argonaut._
  import argonaut.Argonaut._
  import ArgonautShapeless._

  val simpleRecordSource = FlinkSourceFactory.noParam(
    new CollectionSource[SimpleRecord](new ExecutionConfig, List(), Some(new AscendingTimestampExtractor[SimpleRecord] {
      override def extractAscendingTimestamp(element: SimpleRecord) = element.date.getTime
    })), Some(new NewLineSplittedTestDataParser[SimpleRecord] {
      override def parseElement(csv: String): SimpleRecord = {
        val parts = csv.split("\\|")
        SimpleRecord(parts(0), parts(1).toLong, parts(2), new Date(parts(3).toLong), Some(BigDecimal(parts(4))), BigDecimal(parts(5)), parts(6))
      }
    })
  )


  val jsonSource = FlinkSourceFactory.noParam(
    new CollectionSource[SimpleJsonRecord](new ExecutionConfig, List(), None), Some(new EmptyLineSplittedTestDataParser[SimpleJsonRecord] {

      override def parseElement(json: String): SimpleJsonRecord = {
        json.decodeOption[SimpleJsonRecord].get
      }
    })
  )

}