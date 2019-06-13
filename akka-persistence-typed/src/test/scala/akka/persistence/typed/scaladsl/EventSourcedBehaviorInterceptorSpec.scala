/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.Signal
import akka.actor.typed.TypedActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedBehaviorInterceptorSpec {

  val journalId = "event-sourced-behavior-interceptor-spec"

  def config: Config = ConfigFactory.parseString(s"""
        akka.loglevel = INFO
        akka.loggers = [akka.testkit.TestEventListener]
        akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
        """)

  def testBehavior(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    Behaviors.setup { _ =>
      EventSourcedBehavior[String, String, String](
        persistenceId,
        emptyState = "",
        commandHandler = (_, command) =>
          command match {
            case _ =>
              Effect.persist(command).thenRun(newState => probe ! newState)
          },
        eventHandler = (state, evt) => state + evt)
    }

}

class EventSourcedBehaviorInterceptorSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTimersSpec.config)
    with WordSpecLike {

  import EventSourcedBehaviorInterceptorSpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  import akka.actor.typed.scaladsl.adapter._
  // needed for the untyped event filter
  private implicit val untypedSystem: akka.actor.ActorSystem = system.toUntyped

  untypedSystem.eventStream.publish(Mute(EventFilter.warning(start = "No default snapshot store", occurrences = 1)))

  "EventSourcedBehavior interceptor" must {

    "be possible to combine with another interceptor" in {
      val probe = createTestProbe[String]()
      val pid = nextPid()

      val toUpper = new BehaviorInterceptor[String, String] {
        override def aroundReceive(
            ctx: TypedActorContext[String],
            msg: String,
            target: BehaviorInterceptor.ReceiveTarget[String]): Behavior[String] = {
          target(ctx, msg.toUpperCase())
        }

        override def aroundSignal(
            ctx: TypedActorContext[String],
            signal: Signal,
            target: BehaviorInterceptor.SignalTarget[String]): Behavior[String] = {
          target(ctx, signal)
        }

        override def interceptMessageType: Class[_ <: String] = classOf[String]
      }

      val ref = spawn(Behaviors.intercept(toUpper)(testBehavior(pid, probe.ref)))

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("A")
      probe.expectMessage("ABC")
    }

    "be possible to combine with widen" in {
      pending // FIXME #25887
      /*
      java.lang.ClassCastException: akka.persistence.typed.internal.InternalProtocol$RecoveryPermitGranted$ cannot be cast to java.lang.String
        at akka.persistence.typed.scaladsl.EventSourcedBehaviorInterceptorSpec$$anonfun$1.applyOrElse(EventSourcedBehaviorInterceptorSpec.scala:99)
        at akka.actor.typed.internal.WidenedInterceptor.aroundReceive(InterceptorImpl.scala:205)
       */
      val probe = createTestProbe[String]()
      val pid = nextPid()
      val ref = spawn(testBehavior(pid, probe.ref).widen[String] {
        case s => s.toUpperCase()
      })

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("A")
      probe.expectMessage("ABC")
    }

    "be possible to combine with MDC" in {
      pending // FIXME #26953
      /*
      java.lang.ClassCastException: akka.persistence.typed.internal.InternalProtocol$RecoveryPermitGranted$ cannot be cast to java.lang.String
	      at akka.actor.typed.internal.WithMdcBehaviorInterceptor.aroundReceive(WithMdcBehaviorInterceptor.scala:77)
       */
      val probe = createTestProbe[String]()
      val pid = nextPid()
      val ref = spawn(Behaviors.setup[String] { _ =>
        Behaviors
          .withMdc(staticMdc = Map("pid" -> pid), mdcForMessage = (msg: String) => Map("msg" -> msg.toUpperCase())) {
            testBehavior(pid, probe.ref)
          }
      })

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("a")
      probe.expectMessage("abc")

    }

  }
}
