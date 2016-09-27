package io.reactors



import com.typesafe.config._
import java.io._
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ForkJoinPool
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.util.Success
import scala.util.Failure
import scala.util.Try



object Platform {
  class HoconConfiguration(val config: Config) extends Configuration {
    def int(path: String): Int = config.getInt(path)
    def string(path: String): String = config.getString(path)
    def double(path: String): Double = config.getDouble(path)
    def children(path: String): Seq[Configuration] = {
      config.getConfig("remote").root.values.asScala.collect {
        case c: ConfigObject => c.toConfig
      }.map(c => new HoconConfiguration(c)).toSeq
    }
    def withFallback(other: Configuration): Configuration = {
      new HoconConfiguration(this.config.withFallback(
        other.asInstanceOf[HoconConfiguration].config))
    }
  }

  private[reactors] val configurationFactory = new Configuration.Factory {
    def parse(s: String) = new HoconConfiguration(ConfigFactory.parseString(s))
    def empty = new HoconConfiguration(ConfigFactory.empty)
  }

  private[reactors] val machineConfiguration = s"""
    system = {
      num-processors = ${Runtime.getRuntime.availableProcessors}
    }
  """

  private[reactors] val defaultConfiguration = """
    pickler = "io.reactors.pickle.JavaSerializationPickler"
    remote = {
      udp = {
        schema = "reactor.udp"
        transport = "io.reactors.remote.UdpTransport"
        host = "localhost"
        port = 17771
      }
    }
    debug-api = {
      name = "io.reactors.DebugApi$Zero"
      port = 9500
      repl = {
        expiration = 120
        expiration-check-period = 60
      }
      session = {
        expiration = 240
        expiration-check-period = 150
      }
      delta-debugger = {
        window-size = 1024
      }
    }
    scheduler = {
      spindown = {
        initial = 10
        min = 10
        max = 1600
        cooldown-rate = 8
        mutation-rate = 0.15
        test-threshold = 32
        test-iterations = 3
      }
      default = {
        budget = 50
        unschedule-count = 50
      }
    }
    system = {
      net = {
        parallelism = 8
      }
    }
  """

  private[reactors] def registerDefaultSchedulers(b: ReactorSystem.Bundle): Unit = {
    b.registerScheduler(JvmScheduler.Key.globalExecutionContext,
      JvmScheduler.globalExecutionContext)
    b.registerScheduler(JvmScheduler.Key.default, JvmScheduler.default)
    b.registerScheduler(JvmScheduler.Key.newThread, JvmScheduler.newThread)
    b.registerScheduler(JvmScheduler.Key.piggyback, JvmScheduler.piggyback)
  }

  private[reactors] lazy val defaultScheduler = JvmScheduler.default

  object Services {
    /** Contains I/O-related services.
     */
    class Io(val system: ReactorSystem) extends Protocol.Service {
      val defaultCharset = Charset.defaultCharset.name

      def shutdown() {}
    }

    /** Contains common network protocol services.
     */
    class Net(val system: ReactorSystem, private val resolver: URL => InputStream)
    extends Protocol.Service {
      private val networkRequestForkJoinPool = {
        val parallelism = system.config.int("system.net.parallelism")
        new ForkJoinPool(parallelism)
      }
      private implicit val networkRequestContext: ExecutionContext =
        ExecutionContext.fromExecutor(networkRequestForkJoinPool)

      def this(s: ReactorSystem) = this(s, url => url.openStream())

      def shutdown() {
        networkRequestForkJoinPool.shutdown()
      }

      /** Asynchronously retrieves the resource at the given URL.
       *
       *  Once the resource is retrieved, the resulting `IVar` gets a string event with
       *  the resource contents.
       *  In the case of failure, the event stream raises an exception and unreacts.
       *
       *  @param url     the url to load the resource from
       *  @param cs      the name of the charset to use
       *  @return        a single-assignment variable with the resource string
       */
      def resourceAsString(
        url: String, cs: String = system.io.defaultCharset
      ): IVar[String] = {
        val connector = system.channels.daemon.open[Try[String]]
        Future {
          blocking {
            val inputStream = resolver(new URL(url))
            try {
              val sb = new StringBuilder
              val reader = new BufferedReader(new InputStreamReader(inputStream))
              var line = reader.readLine()
              while (line != null) {
                sb.append(line)
                line = reader.readLine()
              }
              sb.toString
            } finally {
              inputStream.close()
            }
          }
        } onComplete {
          case s @ Success(_) =>
            connector.channel ! s
          case f @ Failure(t) =>
            connector.channel ! f
        }
        val ivar = connector.events.map({
          case Success(s) => s
          case Failure(t) => throw t
        }).toIVar
        ivar.ignoreExceptions.onDone(connector.seal())
        ivar
      }
    }
  }

  private[reactors] def inetAddress(host: String, port: Int) =
    new InetSocketAddress(host, port)
}
