package io.reactors
package remote



import java.io._
import java.net._
import java.nio._
import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.concurrent._
import scala.concurrent.duration._



class RemotingTest extends FunSuite with Matchers {

  test("UDP transport should send events correctly") {
    // start server
    val socket = new DatagramSocket
    val server = new Thread {
      var success = false

      class ByteBufferInputStream(val buffer: ByteBuffer) extends InputStream {
        def read() = buffer.get()
        override def read(dst: Array[Byte], offset: Int, length: Int) = {
          val count = math.min(buffer.remaining, length)
          if (count == 0) -1
          else {
            buffer.get(dst, offset, length)
            count
          }
        }
      }

      override def run() {
        val packet = new DatagramPacket(new Array[Byte](1024), 1024)
        socket.receive(packet)
        val buffer = ByteBuffer.wrap(packet.getData, packet.getOffset, packet.getLength)
        
        def read(): Any = {
          val inputStream = new ByteBufferInputStream(buffer)
          val objectInputStream = new ObjectInputStream(inputStream)
          objectInputStream.readObject()
        }

        assert(read() == "test-reactor")
        assert(read() == "test-anchor")
        assert(read() == "test-event")

        success = true
      }
    }
    server.start()

    // start reactor system
    val system = ReactorSystem.default("test-system")
    try {
      val port = socket.getLocalPort
      val sysUrl = SystemUrl("reactor.udp", "localhost", port)
      val channelUrl = ChannelUrl(ReactorUrl(sysUrl, "test-reactor"), "test-anchor")
      val channel = system.remote.resolve[String](channelUrl)

      // send message
      channel ! "test-event"

      // wait for server shutdown
      server.join(9000)

      // check that server completed normally
      assert(server.success)
    } finally system.shutdown()
  }

  test("UDP transport should send and receive events correctly") {
    // start two reactor systems
    val sendSys = ReactorSystem.default(
      "test-send-sys",
      new ReactorSystem.Bundle(Scheduler.default, "remote.udp.port = 0"))
    val recvSys = ReactorSystem.default(
      "test-recv-sys",
      new ReactorSystem.Bundle(Scheduler.default, "remote.udp.port = 0"))
    try {
      // prepare channel
      val sysUrl = SystemUrl("reactor.udp", "localhost",
        recvSys.remote.transport("reactor.udp").asInstanceOf[UdpTransport].port)
      val channelUrl =
        ChannelUrl(ReactorUrl(sysUrl, "test-reactor"), "test-anchor")
      val ch = sendSys.remote.resolve[String](channelUrl)

      // start receiving reactor
      val started = Promise[Boolean]()
      val received = Promise[Boolean]()
      val receiverProto =
        Proto[RemotingTest.UdpReceiver](started, received)
          .withName("test-reactor").withChannelName("test-anchor")
      recvSys.spawn(receiverProto)
      assert(Await.result(started.future, 10.seconds))
      
      // send event and wait
      ch ! "test-event"
      assert(Await.result(received.future, 10.seconds))
    } finally {
      sendSys.shutdown()
      recvSys.shutdown()
    }
  }

}


object RemotingTest {
  class UdpReceiver(val started: Promise[Boolean], val received: Promise[Boolean])
  extends Reactor[String] {
    sysEvents onMatch {
      case ReactorStarted => started.success(true)
    }
    main.events onMatch {
      case "test-event" => received.success(true)
    }
  }
}