/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.actor._
import akka.io.Tcp._
import akka.io.{ IO, Tcp }
import akka.stream.MaterializerSettings
import akka.stream.impl._
import akka.util.ByteString
import org.reactivestreams.Publisher

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 */
private[akka] object TcpListenStreamActor {
  class TcpListenStreamException(msg: String) extends RuntimeException(msg) with NoStackTrace

  def props(bindCmd: Tcp.Bind, requester: ActorRef, settings: MaterializerSettings): Props =
    Props(new TcpListenStreamActor(bindCmd, requester, settings)).withDispatcher(settings.dispatcher)

}

/**
 * INTERNAL API
 */
private[akka] class TcpListenStreamActor(bindCmd: Tcp.Bind, requester: ActorRef, val settings: MaterializerSettings) extends Actor
  with Pump {
  import akka.stream.io.TcpListenStreamActor._
  import context.system

  object primaryOutputs extends FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize, self, pump = this) {
    override def afterShutdown(): Unit = {
      incomingConnections.cancel()
      context.stop(self)
    }

    override def waitingExposedPublisher: Actor.Receive = {
      case ExposedPublisher(publisher) ⇒
        exposedPublisher = publisher
        IO(Tcp) ! bindCmd.copy(handler = self)
        subreceive.become(downstreamRunning)
      case other ⇒
        throw new IllegalStateException(s"The first message must be ExposedPublisher but was [$other]")
    }

    def getExposedPublisher = exposedPublisher
  }

  override protected def pumpFinished(): Unit = incomingConnections.cancel()
  override protected def pumpFailed(e: Throwable): Unit = fail(e)
  override protected def pumpContext: ActorRefFactory = context

  val incomingConnections: Inputs = new DefaultInputTransferStates {
    var listener: ActorRef = _
    private var closed: Boolean = false
    private var pendingConnection: (Connected, ActorRef) = null

    def waitBound: Receive = {
      case Bound(localAddress) ⇒
        listener = sender()
        nextPhase(runningPhase)
        listener ! ResumeAccepting(1)
        requester ! StreamTcp.TcpServerBinding(
          localAddress,
          primaryOutputs.getExposedPublisher.asInstanceOf[Publisher[StreamTcp.IncomingTcpConnection]])
        subreceive.become(running)
      case f: CommandFailed ⇒
        val ex = new TcpListenStreamException("Bind failed")
        requester ! Status.Failure(ex)
        fail(ex)
    }

    def running: Receive = {
      case c: Connected ⇒
        pendingConnection = (c, sender())
        pump()
      case f: CommandFailed ⇒
        fail(new TcpListenStreamException(s"Command [${f.cmd}] failed"))
    }

    override val subreceive = new SubReceive(waitBound)

    override def inputsAvailable: Boolean = pendingConnection ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def isClosed: Boolean = closed
    override def cancel(): Unit = {
      if (!closed && listener != null) listener ! Unbind
      closed = true
      pendingConnection = null
    }
    override def dequeueInputElement(): Any = {
      val elem = pendingConnection
      pendingConnection = null
      listener ! ResumeAccepting(1)
      elem
    }

  }

  private val materialize: Receive = {
    case StreamSupervisor.Materialize(props, wrapper) ⇒
      val impl = context.actorOf(props)
      wrapper match {
        case pub: LazyPublisherLike[Any] ⇒ impl ! ExposedPublisher(pub)
      }
  }

  override def receive: Actor.Receive = materialize orElse primaryOutputs.subreceive orElse incomingConnections.subreceive

  def runningPhase = TransferPhase(primaryOutputs.NeedsDemand && incomingConnections.NeedsInput) { () ⇒
    val (connected: Connected, connection: ActorRef) = incomingConnections.dequeueInputElement()
    val processor = LazyActorProcessor[ByteString, ByteString](TcpStreamActor.inboundProps(connection, settings),
      name = "", materializer = self)
    primaryOutputs.enqueueOutputElement(StreamTcp.IncomingTcpConnection(connected.remoteAddress, processor, processor))
  }

  def fail(e: Throwable): Unit = {
    incomingConnections.cancel()
    primaryOutputs.cancel(e)
  }

}
