package com.protocol7.quincy.tls;

import static com.protocol7.quincy.tls.CipherSuite.TLS_AES_128_GCM_SHA256;
import static com.protocol7.quincy.utils.Hex.dehex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.protocol7.quincy.tls.ClientTlsSession.CertificateInvalidException;
import com.protocol7.quincy.tls.aead.AEAD;
import com.protocol7.quincy.tls.aead.InitialAEAD;
import com.protocol7.quincy.tls.extensions.Extension;
import com.protocol7.quincy.tls.extensions.ExtensionType;
import com.protocol7.quincy.tls.extensions.KeyShare;
import com.protocol7.quincy.tls.extensions.SupportedGroups;
import com.protocol7.quincy.tls.extensions.SupportedVersion;
import com.protocol7.quincy.tls.extensions.SupportedVersions;
import com.protocol7.quincy.tls.extensions.TransportParameters;
import com.protocol7.quincy.tls.messages.ClientHello;
import com.protocol7.quincy.tls.messages.ServerHello;
import com.protocol7.quincy.utils.Bytes;
import com.protocol7.quincy.utils.Rnd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ClientTlsSessionTest {

  private final ClientTlsSession engine =
      new ClientTlsSession(
          InitialAEAD.create(Rnd.rndBytes(4), true),
          TestUtil.tps(),
          new NoopCertificateValidator());
  private final ClientTlsSession started =
      new ClientTlsSession(
          InitialAEAD.create(Rnd.rndBytes(4), true),
          TestUtil.tps(),
          new NoopCertificateValidator());

  @Before
  public void setUp() {
    started.startHandshake();
  }

  @Test
  public void handshake() {
    final byte[] ch = engine.startHandshake();

    final ClientHello hello = ClientHello.parse(ch, false);

    assertEquals(32, hello.getClientRandom().length);
    assertEquals(0, hello.getSessionId().length);
    assertEquals(List.of(TLS_AES_128_GCM_SHA256), hello.getCipherSuites());

    assertEquals(
        32,
        ((KeyShare) hello.getExtension(ExtensionType.KEY_SHARE).get())
            .getKey(Group.X25519)
            .get()
            .length);
    assertEquals(
        List.of(Group.X25519),
        ((SupportedGroups) hello.getExtension(ExtensionType.SUPPORTED_GROUPS).get()).getGroups());
    assertEquals(
        List.of(SupportedVersion.TLS13),
        ((SupportedVersions) hello.getExtension(ExtensionType.SUPPORTED_VERSIONS).get())
            .getVersions());

    final TransportParameters tps =
        (TransportParameters) hello.getExtension(ExtensionType.QUIC).get();
    assertEquals(TestUtil.tps(), tps);
  }

  private KeyShare keyshare() {
    return KeyShare.of(Group.X25519, Rnd.rndBytes(32));
  }

  @Test
  public void serverHello() {
    final List<Extension> ext = List.of(keyshare(), SupportedVersions.TLS13, TestUtil.tps());

    final byte[] b = sh(new byte[32], TLS_AES_128_GCM_SHA256, ext);

    final AEAD aead = started.handleServerHello(b);

    assertNotNull(aead);
    // TODO mock random and test AEAD keys
  }

  private byte[] sh(
      final byte[] serverRandom, final CipherSuite cipherSuite, final List<Extension> ext) {
    final ServerHello sh = new ServerHello(serverRandom, new byte[0], cipherSuite, ext);
    final ByteBuf bb = Unpooled.buffer();
    sh.write(bb);
    return Bytes.drainToArray(bb);
  }

  private List<Extension> ext(final Extension... extensions) {
    return Arrays.asList(extensions);
  }

  @Test(expected = IllegalArgumentException.class)
  public void serverHelloIllegalVersion() {
    final byte[] b =
        dehex(
            "0200009c"
                + "9999"
                + "000000000000000000000000000000000000000000000000000000000000000000130100007400330024001d0020071967d323b1e8362ae9dfdb5280a220b4795019261715f54a6bfc251b17fc45002b000203040ff5004200000000003c0000000400008000000100040000c00000020002006400030002001e0005000205ac00080002006400090000000a000400008000000b000400008000");

    started.handleServerHello(b);
  }

  @Test(expected = IllegalArgumentException.class)
  public void serverHelloNoKeyShare() {
    final byte[] b =
        sh(new byte[32], TLS_AES_128_GCM_SHA256, ext(SupportedVersions.TLS13, TestUtil.tps()));

    started.handleServerHello(b);
  }

  @Test(expected = IllegalArgumentException.class)
  public void serverHelloNoSupportedVersion() {
    final byte[] b = sh(new byte[32], TLS_AES_128_GCM_SHA256, ext(keyshare(), TestUtil.tps()));

    started.handleServerHello(b);
  }

  @Test(expected = IllegalArgumentException.class)
  public void serverHelloIllegalSupportedVersion() {
    final byte[] b =
        dehex(
            "0200009c0303000000000000000000000000000000000000000000000000000000000000000000130100007400330024001d0020071967d323b1e8362ae9dfdb5280a220b4795019261715f54a6bfc251b17fc45002b0002"
                + "9999"
                + "0ff5004200000000003c0000000400008000000100040000c00000020002006400030002001e0005000205ac00080002006400090000000a000400008000000b000400008000");

    started.handleServerHello(b);
  }

  @Test(expected = IllegalStateException.class)
  public void serverHelloWithoutStart() {
    engine.handleServerHello(new byte[0]);
  }

  @Test(expected = IllegalStateException.class)
  public void serverHandshakeWithoutStart() throws CertificateInvalidException {
    engine.handleHandshake(new byte[0]);
  }
}
