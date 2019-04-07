package com.protocol7.nettyquic.client;

import static com.protocol7.nettyquic.connection.State.Closed;
import static com.protocol7.nettyquic.protocol.packets.Packet.getEncryptionLevel;
import static java.util.Optional.of;

import com.protocol7.nettyquic.Pipeline;
import com.protocol7.nettyquic.connection.InternalConnection;
import com.protocol7.nettyquic.connection.PacketSender;
import com.protocol7.nettyquic.connection.State;
import com.protocol7.nettyquic.flowcontrol.FlowControlHandler;
import com.protocol7.nettyquic.logging.LoggingHandler;
import com.protocol7.nettyquic.protocol.ConnectionId;
import com.protocol7.nettyquic.protocol.PacketBuffer;
import com.protocol7.nettyquic.protocol.PacketNumber;
import com.protocol7.nettyquic.protocol.TransportError;
import com.protocol7.nettyquic.protocol.Version;
import com.protocol7.nettyquic.protocol.frames.ConnectionCloseFrame;
import com.protocol7.nettyquic.protocol.frames.Frame;
import com.protocol7.nettyquic.protocol.frames.FrameType;
import com.protocol7.nettyquic.protocol.packets.FullPacket;
import com.protocol7.nettyquic.protocol.packets.HandshakePacket;
import com.protocol7.nettyquic.protocol.packets.InitialPacket;
import com.protocol7.nettyquic.protocol.packets.Packet;
import com.protocol7.nettyquic.protocol.packets.ShortPacket;
import com.protocol7.nettyquic.streams.DefaultStreamManager;
import com.protocol7.nettyquic.streams.Stream;
import com.protocol7.nettyquic.streams.StreamListener;
import com.protocol7.nettyquic.streams.StreamManager;
import com.protocol7.nettyquic.tls.ClientTlsManager;
import com.protocol7.nettyquic.tls.EncryptionLevel;
import com.protocol7.nettyquic.tls.aead.AEAD;
import com.protocol7.nettyquic.tls.extensions.TransportParameters;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;

public class ClientConnection implements InternalConnection {

  private ConnectionId remoteConnectionId;
  private int lastDestConnectionIdLength;
  private Optional<ConnectionId> localConnectionId = of(ConnectionId.random());
  private final PacketSender packetSender;

  private final Version version;
  private final AtomicReference<PacketNumber> sendPacketNumber =
      new AtomicReference<>(new PacketNumber(0));
  private final PacketBuffer packetBuffer;
  private final ClientStateMachine stateMachine;
  private Optional<byte[]> token = Optional.empty();

  private final StreamManager streamManager;
  private final ClientTlsManager tlsManager;
  private final Pipeline pipeline;
  private final InetSocketAddress peerAddress;

  public ClientConnection(
      final Version version,
      final ConnectionId initialRemoteConnectionId,
      final StreamListener streamListener,
      final PacketSender packetSender,
      final FlowControlHandler flowControlHandler,
      final InetSocketAddress peerAddress) {
    this.version = version;
    this.remoteConnectionId = initialRemoteConnectionId;
    this.packetSender = packetSender;
    this.peerAddress = peerAddress;
    this.streamManager = new DefaultStreamManager(this, streamListener);
    this.packetBuffer = new PacketBuffer(this);
    this.tlsManager =
        new ClientTlsManager(remoteConnectionId, TransportParameters.defaults(version.asBytes()));

    final LoggingHandler logger = new LoggingHandler(true);

    this.pipeline =
        new Pipeline(
            List.of(logger, tlsManager, packetBuffer, streamManager, flowControlHandler),
            List.of(packetBuffer, logger));

    this.stateMachine = new ClientStateMachine(this);
  }

  private void resetTlsSession() {
    tlsManager.resetTlsSession(remoteConnectionId);
  }

  public Future<Void> handshake() {
    MDC.put("actor", "client");
    return tlsManager.handshake(getState(), this, stateMachine::setState);
  }

  public Packet sendPacket(final Packet p) {
    if (stateMachine.getState() == Closed) {
      throw new IllegalStateException("Connection not open");
    }

    Packet newPacket = pipeline.send(this, p);

    // check again if any handler closed the connection
    if (stateMachine.getState() == Closed) {
      throw new IllegalStateException("Connection not open");
    }

    sendPacketUnbuffered(newPacket);
    return newPacket;
  }

  public FullPacket send(final Frame... frames) {
    Packet packet;
    if (tlsManager.available(EncryptionLevel.OneRtt)) {
      packet = ShortPacket.create(false, getRemoteConnectionId(), nextSendPacketNumber(), frames);
    } else if (tlsManager.available(EncryptionLevel.Handshake)) {
      packet =
          HandshakePacket.create(
              of(remoteConnectionId), localConnectionId, nextSendPacketNumber(), version, frames);
    } else {
      packet =
          InitialPacket.create(
              of(remoteConnectionId),
              localConnectionId,
              nextSendPacketNumber(),
              version,
              token,
              frames);
    }

    return (FullPacket) sendPacket(packet);
  }

  @Override
  public Optional<ConnectionId> getLocalConnectionId() {
    return localConnectionId;
  }

  @Override
  public Optional<ConnectionId> getRemoteConnectionId() {
    return Optional.ofNullable(remoteConnectionId);
  }

  public void setRemoteConnectionId(final ConnectionId remoteConnectionId, boolean retry) {
    this.remoteConnectionId = remoteConnectionId;

    if (retry) {
      resetTlsSession();
    }
  }

  public Optional<byte[]> getToken() {
    return token;
  }

  public void setToken(byte[] token) {
    this.token = of(token);
  }

  public int getLastDestConnectionIdLength() {
    return lastDestConnectionIdLength;
  }

  private void sendPacketUnbuffered(final Packet packet) {
    packetSender
        .send(packet, getAEAD(getEncryptionLevel(packet)))
        .awaitUninterruptibly(); // TODO fix
  }

  public void onPacket(final Packet packet) {
    if (packet.getDestinationConnectionId().isPresent()) {
      lastDestConnectionIdLength = packet.getDestinationConnectionId().get().getLength();
    } else {
      lastDestConnectionIdLength = 0;
    }

    EncryptionLevel encLevel = getEncryptionLevel(packet);
    if (tlsManager.available(encLevel)) {
      stateMachine.handlePacket(packet);
      if (getState() != State.Closed) {
        pipeline.onPacket(this, packet);
      }
    } else {
      // TODO handle unencryptable packet
    }
  }

  @Override
  public AEAD getAEAD(final EncryptionLevel level) {
    return tlsManager.getAEAD(level);
  }

  public Version getVersion() {
    return version;
  }

  public PacketNumber nextSendPacketNumber() {
    return sendPacketNumber.updateAndGet(packetNumber -> packetNumber.next());
  }

  public void resetSendPacketNumber() {
    sendPacketNumber.set(new PacketNumber(0));
  }

  public Stream openStream() {
    return streamManager.openStream(true, true);
  }

  public Future<Void> close(
      final TransportError error, final FrameType frameType, final String msg) {
    stateMachine.closeImmediate(
        ConnectionCloseFrame.connection(error.getValue(), frameType.getType(), msg));

    return packetSender.destroy();
  }

  @Override
  public InetSocketAddress getPeerAddress() {
    return peerAddress;
  }

  public Future<Void> close() {
    stateMachine.closeImmediate();

    return packetSender.destroy();
  }

  public Future<Void> closeByPeer() {
    return packetSender.destroy();
  }

  public State getState() {
    return stateMachine.getState();
  }

  public void setState(final State state) {
    stateMachine.setState(state);
  }
}
