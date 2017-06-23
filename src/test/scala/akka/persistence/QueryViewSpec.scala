package akka.persistence

import akka.actor.{ActorRef, Props, Status, Terminated}
import akka.contrib.persistence.query.LevelDbQuerySupport
import akka.pattern._
import akka.persistence.QueryView.ForceUpdate
import akka.persistence.journal.Tagged
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import akka.util.Timeout
import com.ovoenergy.akka.{AkkaFixture, AkkaPersistenceFixture}
import com.ovoenergy.{ConfigFixture, UnitSpec}

import scala.concurrent.Promise
import scala.concurrent.duration._

class QueryViewSpec extends UnitSpec with ConfigFixture with AkkaFixture with AkkaPersistenceFixture {

  import TestQueryView._

  private implicit val askTimeout = Timeout(15.seconds)
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(scaled(5.seconds), scaled(50.milliseconds))

  "QueryView" when {
    "has a persistenceId based query" when {
      "is running" should {
        "receive live events with the given persistenceId" in new PersistenceIdQueryViewContext("test-1") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          val expectedMessages = Seq("test-1-1", "test-1-2", "test-1-3")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }
      }

      "is restarted" should {
        "receive journal events with the given persistenceId" in new PersistenceIdQueryViewContext("test-1") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          restartUnderTest()

          val expectedMessages = Seq("test-1-1", "test-1-2", "test-1-3")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "continue receiving live events with the given persistenceId" in new PersistenceIdQueryViewContext("test-1") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          restartUnderTest()

          writeToJournal("test-1", Tagged("test-1-4", Set("one")))
          writeToJournal("test-2", Tagged("test-2-3", Set("one")))

          val expectedMessages = Seq("test-1-1", "test-1-2", "test-1-3", "test-1-4")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "receives events from new recoverystream on force update" in new PersistenceIdQueryViewContextOnlyRecoveryStream(
          "test-1"
        ) {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          restartUnderTest()

          val recoveryMessages = Seq("test-1-1", "test-1-2", "test-1-3")
          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs recoveryMessages
          }

          writeToJournal("test-1", Tagged("test-1-4", Set("one")))
          writeToJournal("test-2", Tagged("test-2-3", Set("one")))

          val expectedMessages = Seq("test-1-1", "test-1-2", "test-1-3", "test-1-4")
          eventually {
            forceUpdate()
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "load status from snapshot and receive journal events" in new PersistenceIdQueryViewContext("test-1") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          saveSnapshot()
          deleteFromJournal("test-1", 3L)

          writeToJournal("test-1", Tagged("test-1-4", Set("one")))
          writeToJournal("test-2", Tagged("test-2-3", Set("one")))

          restartUnderTest()

          val expectedMessages = Seq("test-1-1", "test-1-2", "test-1-3", "test-1-4")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }
      }
    }
    "has a tag based query" when {
      "is running" should {
        "receive live events with the given tag" in new TagQueryViewContext("one") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-1", Set("two")))
          writeToJournal("test-3", Tagged("test-3-1", Set("one")))
          writeToJournal("test-4", Tagged("test-4-1", Set("two")))
          writeToJournal("test-1", Tagged("test-1-2", Set("one")))
          writeToJournal("test-3", Tagged("test-3-2", Set("two")))

          val expectedMessages = Seq("test-1-1", "test-3-1", "test-1-2")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }
      }
      "is restarted" should {
        "receive journal events with the given tag" in new TagQueryViewContext("one") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-1", Set("two")))
          writeToJournal("test-3", Tagged("test-3-1", Set("one")))
          writeToJournal("test-4", Tagged("test-4-1", Set("two")))
          writeToJournal("test-1", Tagged("test-1-2", Set("one")))
          writeToJournal("test-3", Tagged("test-3-2", Set("two")))

          restartUnderTest()

          val expectedMessages = Seq("test-1-1", "test-3-1", "test-1-2")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "continue receiving live events with the given tag" in new TagQueryViewContext("one") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one", "two")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          restartUnderTest()

          writeToJournal("test-1", Tagged("test-1-4", Set("one")))
          writeToJournal("test-2", Tagged("test-2-3", Set("one", "two")))

          val expectedMessages = Seq("test-1-1", "test-2-1", "test-1-3", "test-1-4", "test-2-3")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "receives events from new recoverystream on force update" in new TagQueryViewContextOnlyRecoveryStream("one") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          restartUnderTest()

          val recoveryMessages = Seq("test-1-1", "test-2-1", "test-1-3")
          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs recoveryMessages
          }

          writeToJournal("test-1", Tagged("test-1-4", Set("one")))
          writeToJournal("test-2", Tagged("test-2-3", Set("one")))

          val expectedMessages = Seq("test-1-1", "test-2-1", "test-1-3", "test-1-4", "test-2-3")
          eventually {
            forceUpdate()
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "load status from snapshot and receive journal events" in new TagQueryViewContext("one") {

          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("two")))
          writeToJournal("test-2", Tagged("test-2-1", Set("one")))
          writeToJournal("test-2", Tagged("test-2-2", Set("two")))
          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          saveSnapshot()
          deleteFromJournal("test-1", 3L)

          writeToJournal("test-1", Tagged("test-1-4", Set("one")))
          writeToJournal("test-2", Tagged("test-2-3", Set("one", "two")))

          restartUnderTest()

          val expectedMessages = Seq("test-1-1", "test-2-1", "test-1-3", "test-1-4", "test-2-3")

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs expectedMessages
          }
        }

        "recover from failure in live stream" in new FailingLiveQueryViewContext("one") {
          writeToJournal("test-1", Tagged("test-1-1", Set("one")))
          writeToJournal("test-1", Tagged("test-1-2", Set("one")))

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs Seq("test-1-1", "test-1-2")
          }

          writeToJournal("test-1", Tagged("test-1-3", Set("one")))

          eventually {
            val receivedMessages = underTest.ask(GetMessage).mapTo[Vector[String]].futureValue
            receivedMessages should contain theSameElementsInOrderAs Seq("test-1-1", "test-1-2", "test-1-3")
          }
        }
      }
    }
  }

  trait QueryViewContext {

    private var _underTest: ActorRef = createUnderTest()
    def underTest: ActorRef = _underTest

    def restartUnderTest(): Unit = {
      val probe = TestProbe()
      probe.watch(underTest)
      system.stop(underTest)
      probe.expectMsgType[Terminated]

      _underTest = createUnderTest()
    }

    protected def createUnderTest(): ActorRef

    def saveSnapshot(): Unit = {
      val probe = TestProbe()
      probe.send(underTest, SaveSnapshot)
      probe.expectMsg(SnapshotSaved)
    }

    def forceUpdate(): Unit = {
      val probe = TestProbe()
      probe.send(underTest, ForceUpdate)
    }
  }

  class PersistenceIdQueryViewContext(persistenceId: String) extends QueryViewContext {

    override protected def createUnderTest(): ActorRef =
      system.actorOf(Props(new PersistenceIdQueryView(persistenceId)))
  }

  class PersistenceIdQueryViewContextOnlyRecoveryStream(persistenceId: String) extends QueryViewContext {

    override protected def createUnderTest(): ActorRef =
      system.actorOf(Props(new PersistenceIdQueryViewOnlyRecoveryStream(persistenceId)))
  }

  class TagQueryViewContext(tag: String) extends QueryViewContext {

    override protected def createUnderTest(): ActorRef =
      system.actorOf(Props(new TagQueryView(tag)))
  }

  class TagQueryViewContextOnlyRecoveryStream(tag: String) extends QueryViewContext {

    override protected def createUnderTest(): ActorRef =
      system.actorOf(Props(new TagQueryViewOnlyRecoveryStream(tag)))
  }

  class FailingLiveQueryViewContext(tag: String) extends QueryViewContext {

    private var fail = true

    override protected def createUnderTest(): ActorRef = {
      val actor = system.actorOf(Props(new FailingLiveQueryView(tag, fail)))
      fail = false
      actor
    }

  }
}

class PersistenceIdQueryView(persistenceId: String) extends TestQueryView {

  override def recoveringStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    queries.currentEventsByPersistenceId(
      persistenceId,
      sequenceNrByPersistenceId.get(persistenceId).map(_ + 1).getOrElse(0)
    )

  override def liveStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    queries.eventsByPersistenceId(persistenceId, sequenceNrByPersistenceId.get(persistenceId).map(_ + 1).getOrElse(0))
}

class PersistenceIdQueryViewOnlyRecoveryStream(persistenceId: String) extends PersistenceIdQueryView(persistenceId) {

  override def liveStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    Source.fromFuture(Promise().future) //never ending stream without elements
}

class TagQueryView(tag: String) extends TestQueryView {

  override def recoveringStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    queries.currentEventsByTag(tag, lastOffset)

  override def liveStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    queries.eventsByTag(tag, lastOffset)
}

class TagQueryViewOnlyRecoveryStream(tag: String) extends TagQueryView(tag) {

  override def liveStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    Source.fromFuture(Promise().future) //never ending stream without elements
}

class FailingLiveQueryView(tag: String, var fail: Boolean) extends TagQueryView(tag) {

  override def recoveringStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] =
    queries.currentEventsByTag(tag, lastOffset)

  override def liveStream(sequenceNrByPersistenceId: Map[String, Long], lastOffset: OT): Source[AnyRef, _] = {
    if (fail) {
      Source.failed(new RuntimeException("Live failed."))
    } else {
      super.liveStream(sequenceNrByPersistenceId, lastOffset)
    }
  }
}

object TestQueryView {
  val GetMessage = "GetMessages"
  val SaveSnapshot = "SaveSnapshot"
  val SnapshotSaved = "SnapshotSaved"
}

abstract class TestQueryView extends QueryView with LevelDbQuerySupport {
  import TestQueryView._

  private var messages: Vector[String] = Vector.empty

  private var waitForSnapshot = Option.empty[ActorRef]

  /**
    * It is the persistenceId linked to this view. It should be unique.
    */
  override def snapshotterId: String = "test"

  override def receive: Receive = {

    case SaveSnapshot =>
      saveSnapshot(messages)
      waitForSnapshot = Some(sender())

    case SaveSnapshotSuccess(_) =>
      waitForSnapshot.foreach(_ ! SnapshotSaved)
      waitForSnapshot = None

    case SaveSnapshotFailure(_, error) =>
      waitForSnapshot.foreach(_ ! Status.Failure(error))
      waitForSnapshot = None

    case SnapshotOffer(_, snapshot: Vector[String]) =>
      messages = snapshot

    case GetMessage =>
      sender() ! messages

    case message: String =>
      messages = messages :+ message
  }

}
