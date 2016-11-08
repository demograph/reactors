package io.reactors
package protocol






trait TwoWayProtocols {
  /** Represents the state of an established 2-way channel.
   *
   *  To close the 2-way channel, use the associated `Subscription` object.
   *
   *  @tparam I              type of the incoming events
   *  @tparam O              type of the outgoing events
   *  @param output          the output channel, for outgoing events
   *  @param input           the input event stream, for incoming events
   *  @param subscription    subscription associated with this 2-way connection
   */
  case class TwoWay[I, O](
    output: Channel[O], input: Events[I], subscription: Subscription
  ) extends Connection[I] {
    /** Same as `input`, events provided by this connection.
     */
    def events = input

    /** Same as `output`, channel provided to write with this connection.
     */
    def channel = output
  }

  object TwoWay {
    /** Two-way server object.
     *
     *  @tparam I            type of the server-side input events
     *  @tparam O            type of the server-side output events
     *  @param channel       request channel for establishing 2-way connections
     *  @param connections   event stream that emits established connections
     *  @param subscription  subscription for the server and its resources
     */
    case class Server[I, O](
      channel: io.reactors.protocol.Server[Channel[I], Channel[O]],
      connections: Events[TwoWay[O, I]],
      subscription: Subscription
    ) extends ServerSide[Req[I, O], TwoWay[O, I]]

    /** Request object for 2-way server channels.
     *
     *  @tparam I            type of the server-side input events
     *  @tparam O            type of the server-side output events
     */
    type Req[I, O] = io.reactors.protocol.Server.Req[Channel[I], Channel[O]]
  }

  implicit class TwoWayChannelBuilderOps(val builder: ChannelBuilder) {
    /** Creates a connector for a 2-way server.
     *
     *  The connector is just a placeholder, but creating it does not yet run the
     *  2-way protocol. To start the 2-way protocol, users must call the `serverTwoWay`
     *  method.
     *
     *  @tparam I            type of the server-side input events
     *  @tparam O            type of the server-side output events
     *  @return              the server connector
     */
    def twoWayServer[
      @spec(Int, Long, Double) I, @spec(Int, Long, Double) O
    ]: Connector[TwoWay.Req[I, O]] = {
      builder.open[TwoWay.Req[I, O]]
    }
  }

  implicit class TwoWayConnectorOps[
    @spec(Int, Long, Double) I, @spec(Int, Long, Double) O
  ](val connector: Connector[TwoWay.Req[I, O]]) {
    /** Starts a 2-way server protocol.
     *
     *  This method can be called on a previously created 2-way server connector
     *  (see `twoWayServer`).
     */
    def serveTwoWay()(implicit a: Arrayable[O]): TwoWay.Server[I, O] = {
      val connections = connector.events map {
        case (outputChannel, reply) =>
          val system = Reactor.self.system
          val output = system.channels.daemon.open[O]
          reply ! output.channel
          TwoWay(outputChannel, output.events, Subscription(output.seal()))
      } toEmpty

      TwoWay.Server(
        connector.channel,
        connections,
        connections.chain(Subscription(connector.seal()))
      )
    }
  }

  implicit class TwoWayServerOps[
    @spec(Int, Long, Double) I, @spec(Int, Long, Double) O
  ](val twoWayServer: Channel[TwoWay.Req[I, O]]) {
    /** Connects to a 2-way protocol server.
     *
     *  A successful connection yields a 2-way channel object of type `TwoWay[I, O]`.
     */
    def connect()(implicit a: Arrayable[I]): IVar[TwoWay[I, O]] = {
      val system = Reactor.self.system
      val output = system.channels.daemon.open[I]
      val result: Events[TwoWay[I, O]] = (twoWayServer ? output.channel) map {
        inputChannel =>
        TwoWay(inputChannel, output.events, Subscription(output.seal()))
      }
      result.toIVar
    }
  }

  implicit class TwoWayReactorCompanionOps(val reactor: Reactor.type) {
    /** Create a `Proto` object for a 2-way server reactor.
     *
     *  @tparam I       type of the server-side input events
     *  @tparam O       type of the server-side output events
     *  @param f        callback function for a successful incoming connection
     *  @return         a `Proto` object
     */
    def twoWayServer[@spec(Int, Long, Double) I, @spec(Int, Long, Double) O](
      f: (TwoWay.Server[I, O], TwoWay[O, I]) => Unit
    )(implicit ai: Arrayable[I], ao: Arrayable[O]): Proto[Reactor[TwoWay.Req[I, O]]] = {
      Reactor[TwoWay.Req[I, O]] { self =>
        val server = self.main.serveTwoWay()
        server.connections.onEvent(twoWay => f(server, twoWay))
      }
    }
  }

  implicit class TwoWaySystemOps(val system: ReactorSystem) {
    /** Create a `Proto` object for a 2-way server reactor.
     *
     *  @tparam I       type of the server-side input events
     *  @tparam O       type of the server-side output events
     *  @param f        callback function for a successful incoming connection
     *  @return         a channel of the 2-way server
     */
    def twoWayServer[@spec(Int, Long, Double) I, @spec(Int, Long, Double) O](
      f: (TwoWay.Server[I, O], TwoWay[O, I]) => Unit
    )(implicit ai: Arrayable[I], ao: Arrayable[O]): Channel[TwoWay.Req[I, O]] = {
      val proto = Reactor.twoWayServer(f)
      system.spawn(proto)
    }
  }
}
