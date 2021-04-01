package de.uulm.vs.extendeddcn;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.crypto.KeyAgreement;

import com.amihaiemil.eoyaml.Scalar;
import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.uulm.vs.extendeddcn.util.Address;
import de.uulm.vs.extendeddcn.util.Pair;
import de.uulm.vs.extendeddcn.util.Util;

public class Dcn {
  private static Logger LOGGER = LogManager.getLogger();

  private final Settings SETTINGS;
  private final Map<Integer, SocketChannel> peerSockets;
  private final Map<Integer, Random> randomNumberGenerators;
  private final SplitCombineStrategy splitCombineStrategy;
  private final SendReceiveThread sendReceiveThread;
  private final Thread sendReceiveThreadHandle;
  private final Queue<Byte> assmbledRecBuf = new ArrayDeque<>();

  public Dcn(final Settings settings) {
    SETTINGS = settings;
    sendReceiveThread = new SendReceiveThread();
    peerSockets = runSocketInit();
    randomNumberGenerators = runInitialKeyShare();
    splitCombineStrategy = settings.getK() == 1 ? new DummySplitCombine(settings.peers.size() + 1)
        : new ShamirSplitCombine(settings.peers.size() + 1, settings.getK());

    LOGGER.debug("SplitCombineStrategy: " + splitCombineStrategy.getClass().getName() + " with n="
        + splitCombineStrategy.n + ", k=" + splitCombineStrategy.k);
    sendReceiveThreadHandle = new Thread(sendReceiveThread);
    sendReceiveThreadHandle.start();
  }

  public void broadcast(final byte[] msg) throws IOException {
    LOGGER.info("Broadcasting: " + Arrays.toString(msg));
    for (final byte bt : msg) {
      sendReceiveThread.toSend.add(bt);
    }
  }

  public Map<Integer, byte[]> recvShares() throws InterruptedException {
    LOGGER.trace("attempting take");
    var nextShares = sendReceiveThread.toReceive.take();
    LOGGER.trace("take succeeded");

    // The first 4 bytes in the received messages are not part of the share, but
    // represent the size of the sent message.
    // In order to be able to reassemble the shares, we need to discard these bytes:
    for (var i : nextShares.keySet()) {
      var arrWithSz = new byte[this.SETTINGS.getBytesPerRound()];
      System.arraycopy(nextShares.get(i), 4, arrWithSz, 0, Dcn.this.SETTINGS.getBytesPerRound());

      nextShares.put(i, arrWithSz);

    }
    return nextShares;
  }

  public int recv(byte[] buf) throws InterruptedException {
    var bytesRead = 0;
    // are there any bytes left that have been reassembled from a previous call to
    // recv()?
    while (bytesRead < buf.length && !assmbledRecBuf.isEmpty()) {
      buf[bytesRead++] = assmbledRecBuf.poll();
    }
    LOGGER.trace("RECV: took " + bytesRead + "b from last round...");
    while (bytesRead < buf.length) {
      var recvShares = sendReceiveThread.toReceive.take();
      LOGGER.trace("Message shares I use to reassemble message: " + Util.mapToString(recvShares));
      final var msg = splitCombineStrategy.combine(recvShares);
      int dataSz = ((int) msg[0] << 24) | ((int) msg[1] << 16) | ((int) msg[2] << 8) | (msg[3]);
      int copiedBytes = Math.min(dataSz, buf.length - bytesRead);
      System.arraycopy(msg, 4, buf, bytesRead, copiedBytes);
      bytesRead += copiedBytes;
      while (copiedBytes < dataSz) {
        assmbledRecBuf.add(msg[2 + copiedBytes++]);
      }
    }
    return bytesRead;
  }

  public void shutdown() throws InterruptedException {
    sendReceiveThread.running = false;
    sendReceiveThreadHandle.join();
  }

  /**
   * Establishes a shared secret with all peers.
   * 
   * @return Map containing pseudo-random number generators seeded with the shared
   *         secrets.
   */
  private Map<Integer, Random> runInitialKeyShare() {
    final var rngs = new ConcurrentHashMap<Integer, Random>();
    final int keysize = 2048;
    try {
      for (final var peer : peerSockets.entrySet()) {
        final var peerNr = peer.getKey();
        final var peerSoc = peer.getValue();

        final KeyPairGenerator ownKPGen = KeyPairGenerator.getInstance("DH");
        ownKPGen.initialize(keysize);
        final KeyPair ownKP = ownKPGen.generateKeyPair();
        final KeyAgreement ownKA = KeyAgreement.getInstance("DH");
        ownKA.init(ownKP.getPrivate());
        final byte[] ownPK = ownKP.getPublic().getEncoded();

        sendByteArray(peerSoc, String.format("%05d", ownPK.length).getBytes());
        sendByteArray(peerSoc, ownPK);
        LOGGER.debug("Sent " + ownPK.length + " bytes for our public key");

        final var otherKeyLenRecvBuf = ByteBuffer.allocate(5);
        for (var read = 0; read < otherKeyLenRecvBuf.capacity();) {
          read += peerSoc.read(otherKeyLenRecvBuf);
        }
        final int otherKeyLen = Integer.parseInt(new String(otherKeyLenRecvBuf.array()));
        LOGGER.trace("Friendly public key has length of " + otherKeyLen);

        final var otherPKEnc = ByteBuffer.allocate(otherKeyLen);
        for (var read = 0; read < otherPKEnc.capacity();) {
          read += peerSoc.read(otherPKEnc);
        }
        LOGGER.debug("Got encoded public key " + Util.toHexString(otherPKEnc.array()));
        final KeyFactory keyFact = KeyFactory.getInstance("DH");
        final X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(otherPKEnc.array());
        final PublicKey otherPK = keyFact.generatePublic(x509KeySpec);
        ownKA.doPhase(otherPK, true);

        final byte[] sharedSecret = ownKA.generateSecret();
        LOGGER.trace("Our shared secret with " + peerNr + ": " + Util.toHexString(sharedSecret));
        rngs.put(peerNr, new Random(seedFromKey(sharedSecret)));
        LOGGER.trace("Our shared rng seed with " + peerNr + ": " + seedFromKey(sharedSecret));
        LOGGER.debug("Key exchange with " + peerNr + " successful!");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException | IOException e) {
      e.printStackTrace();
      System.exit(1);
    }

    LOGGER.info("All key exchanges successful");

    return rngs;
  }

  // Establish TCP connections to all peers.
  private Map<Integer, SocketChannel> runSocketInit() {
    final var executorService = Executors.newFixedThreadPool(SETTINGS.getPeers().size());
    final var sockets = new ConcurrentHashMap<Integer, SocketChannel>(SETTINGS.getPeers().size());

    // server thread that will accept incoming connections
    executorService.submit(() -> {
      final Set<Pair<Integer, Address>> peersNeeded = SETTINGS.getPeers().entrySet().stream()
          .filter(entry -> SETTINGS.ownIP.getValue().compareTo(entry.getValue()) > 0).map(Pair::fromEntry)
          .collect(Collectors.toSet());

      LOGGER.trace(this.SETTINGS.getOwnIP().getValue().toString() + ": Creating serversocket for: " + peersNeeded);
      var connectionsAccepted = 0;

      try (ServerSocketChannel ssoc = ServerSocketChannel.open()) {
        ssoc.socket().bind(new InetSocketAddress(SETTINGS.ownIP.getValue().getPort()));
        while (connectionsAccepted < peersNeeded.size()) {
          try {
            LOGGER.debug("(ServerSocket) Ready for incoming connections, already got " + connectionsAccepted + "/"
                + peersNeeded.size());
            final SocketChannel soc = ssoc.accept();

            int remoteLocalPort;
            // receive hello message
            {
              var buf = ByteBuffer.allocate(2);
              for (var bytesRec = 0; bytesRec < 2;) {
                bytesRec += soc.read(buf);
              }
              buf.flip();
              remoteLocalPort = buf.getShort();
            }

            final var pAddr = soc.socket().getInetAddress();
            final var remotePeerConfigO = peersNeeded.stream()
                .filter(pia -> pia.getValue().getHost().equals(pAddr.getHostAddress())
                    && pia.getValue().getPort() == remoteLocalPort)
                .findFirst();

            LOGGER.debug("(ServerSocket) Incoming connection from " + pAddr + ":" + soc.socket().getPort()
                + ". Remote local port: " + remoteLocalPort);
            if (remotePeerConfigO.isPresent()) {
              final var remotePeerConfig = remotePeerConfigO.get();
              sockets.put(remotePeerConfig.getKey(), soc);
              ++connectionsAccepted;
              LOGGER.info(
                  "(ServerSocket) Accepted peer" + remotePeerConfig + "(" + pAddr + ":" + soc.socket().getPort() + ")");
            } else {
              LOGGER.debug("(ServerSocket) Refused " + pAddr + ":" + soc.socket().getPort());
              soc.close();
            }
          } catch (final IOException e) {
            e.printStackTrace();
          }
        }
      } catch (final IOException e) {
        e.printStackTrace();
      }
      LOGGER.trace("(ServerSocket) terminating");
    });

    SETTINGS.getPeers().forEach((id, address) -> {
      if (SETTINGS.ownIP.getValue().compareTo(address) < 0) {
        executorService.submit(() -> {
          try {
            LOGGER.debug("(ClientSocket) Sleeping to give " + address.getHost() + ":" + address.getPort()
                + " time to initialise");
            Thread.sleep(5_000);
            LOGGER.debug("(ClientSocket) Trying to connect to " + address.getHost() + ":" + address.getPort());
            final var soc = SocketChannel.open();
            soc.connect(new InetSocketAddress(address.getHost(), address.getPort()));
            sockets.put(id, soc);

            // write hello message (consisting of 2 bytes naming the port)
            var msg = ByteBuffer.allocate(2).putShort((short) this.SETTINGS.getOwnIP().getValue().getPort());
            msg.flip();
            while (msg.hasRemaining()) {
              soc.write(msg);
            }

            LOGGER.info("(ClientSocket) Connected to " + address.getHost() + ":" + address.getPort());
          } catch (IOException | InterruptedException e) {
            e.printStackTrace();
          }
        });
      }
    });

    shutdownAndAwaitTermination(executorService);
    // for now, we use nonblocking mode.
    sockets.forEachValue(Long.MAX_VALUE, soc -> {
      try {
        soc.configureBlocking(false);
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
    LOGGER.info("All " + sockets.size() + " connections ready");

    LOGGER.trace(this.SETTINGS.getOwnIP().getValue().toString() + sockets);
    return sockets;
  }

  // from:
  // https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ExecutorService.html
  private static void shutdownAndAwaitTermination(final ExecutorService pool) {
    pool.shutdown(); // Disable new tasks from being submitted
    try {
      // Wait a while for existing tasks to terminate
      if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
        pool.shutdownNow(); // Cancel currently executing tasks
        // Wait a while for tasks to respond to being cancelled
        if (!pool.awaitTermination(60, TimeUnit.SECONDS))
          System.err.println("Pool did not terminate");
      }
    } catch (final InterruptedException ie) {
      // (Re-)Cancel if current thread also interrupted
      pool.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }
  }

  private static long seedFromKey(final byte[] key) {
    long seed = 0;
    for (int i = 0; i < 64 / 8; i++) {
      seed ^= ((long) key[i]) << i * 8;
    }
    return seed;
  }

  private static void sendByteArray(SocketChannel soc, byte[] msg) throws IOException {
    var buf = ByteBuffer.wrap(msg);
    while (buf.hasRemaining()) {
      soc.write(buf);
    }
  }

  /**
   * Background Thread for receiving and sending messages.
   */
  final private class SendReceiveThread implements Runnable {
    public final BlockingQueue<Byte> toSend = new LinkedBlockingQueue<>();
    public final BlockingQueue<Map<Integer, byte[]>> toReceive = new LinkedBlockingQueue<>();
    public boolean running = true;
    private short currentSeqNr = 1;
    private int msgSize = Frame.msgSize(Dcn.this.SETTINGS.getBytesPerRound());
    private int msgDataSize = Dcn.this.SETTINGS.getBytesPerRound() + 4;

    @Override
    public void run() {
      while (running || !toSend.isEmpty()) {
        try {
          Thread.sleep(Dcn.this.SETTINGS.roundInterval);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
        // Protocol Stage 1

        // we acquire the next message to send from queuedMessages.
        // if there are no or less than BYTES_PER_ROUND bytes to send,
        // we leave the remaining bytes as 0s.
        final var bytesToSend = new byte[msgDataSize];
        short usefulDataInThisMessage = 0;
        for (var i = 0; i < Dcn.this.SETTINGS.getBytesPerRound() && !toSend.isEmpty(); ++i) {
          bytesToSend[i] = toSend.poll();
          ++usefulDataInThisMessage;
        }

        LOGGER.trace("I'm sending the following message: " + Arrays.toString(bytesToSend));
        final Map<Integer, byte[]> msgParts = new HashMap<>();
        ;
        if (usefulDataInThisMessage == 0) {
          for (var i = 1; i <= Dcn.this.peerSockets.size() + 1; ++i) {
            msgParts.put(i, new byte[msgDataSize]);
          }
        } else {
          var splitMsg = splitCombineStrategy.split(bytesToSend);
          for (var i = 1; i <= Dcn.this.peerSockets.size() + 1; ++i) {
            var arrWithSz = new byte[msgDataSize];
            System.arraycopy(splitMsg.get(i), 0, arrWithSz, 4, Dcn.this.SETTINGS.getBytesPerRound());
            // first four bytes in each round are msg size.
            arrWithSz[0] = (byte) ((usefulDataInThisMessage >> 24) & 0xff);
            arrWithSz[1] = (byte) ((usefulDataInThisMessage >> 16) & 0xff);
            arrWithSz[2] = (byte) ((usefulDataInThisMessage >> 8) & 0xff);
            arrWithSz[3] = (byte) (usefulDataInThisMessage & 0xff);

            msgParts.put(i, arrWithSz);

          }
        }

        LOGGER.trace("My message parts: " + Util.mapToString(msgParts));

        // we acquire the next shared secret with each of the peers
        // from the RNGs and save it into sharedSecrets.
        var sharedSecrets = genSharedSecrets(msgDataSize);

        LOGGER.trace("My shared secrets: " + Util.mapToString(sharedSecrets));

        // we xor all the secrets with the message we intend to send.
        // because we send n different messages, we have to to this for each
        // recipient.
        sharedSecrets.values().forEach(secret -> {
          msgParts.values().forEach(msg -> {
            for (var i = 0; i < msgDataSize; i++) {
              msg[i] ^= secret[i];
            }
          });
        });

        try {
          for (final var entry : peerSockets.entrySet()) {
            final var soc = entry.getValue();
            final var peerID = entry.getKey();
            final var frameToSend = new Frame(currentSeqNr, FrameType.DCN_ROUND, msgParts.get(peerID + 1));

            LOGGER.trace("Sending to " + peerID + ": " + Arrays.toString(msgParts.get(peerID + 1)));

            var buf = frameToSend.serialize();
            while (buf.hasRemaining()) {
              var written = soc.write(buf);
              LOGGER.trace("Wrote " + written + " bytes to " + peerID);
            }
          }
        } catch (IOException e) {
          LOGGER.error("Error sending message part ", e);
        }

        LOGGER.trace("DCN stage 1 finished, commencing with phase 2");

        // Protocol Stage 2
        final var lastMessagePart = new byte[msgDataSize];
        System.arraycopy(msgParts.get(Dcn.this.SETTINGS.ownIP.getKey() + 1), 0, lastMessagePart, 0,
            lastMessagePart.length);

        try {
          for (final var entry : peerSockets.entrySet()) {
            final var soc = entry.getValue();
            final var recvbuf = ByteBuffer.allocate(msgSize);
            for (var read = 0; read < recvbuf.capacity();) {
              if (!soc.isOpen()) {
                throw new IOException("Partner " + entry.getKey() + " disconnected.");
              }
              read += soc.read(recvbuf);
            }
            var recvFrame = Frame.deserialize(recvbuf.flip());

            LOGGER.trace("Phase 2: Read from partner " + entry.getKey());

            for (var i = 0; i < msgDataSize; ++i) {
              lastMessagePart[i] ^= recvFrame.getData()[i];
            }

          }
        } catch (IOException e) {
          LOGGER.error("Error receiving message part ", e);
        }

        // Distribute message shares to all other players
        try {
          var bArr = new Frame(currentSeqNr, FrameType.SHARE_FLOODING, lastMessagePart).serialize();
          for (var peerSocE : peerSockets.entrySet()) {
            if (!Dcn.this.SETTINGS.sendMessagePartsTo.contains(peerSocE.getKey())) {
              continue;
            }
            var peerSoc = peerSocE.getValue();
            while (bArr.hasRemaining()) {
              peerSoc.write(bArr);
            }
            bArr.flip();
          }
          currentSeqNr++;
        } catch (IOException e) {
          LOGGER.error("Error distributing message share ", e);
        }

        // receive message shares from all other players
        final var recvShares = new HashMap<Integer, byte[]>(Dcn.this.SETTINGS.k);
        recvShares.put(Dcn.this.SETTINGS.ownIP.getKey() + 1, lastMessagePart);
        try {
          for (var peerEntry : peerSockets.entrySet()) {
            if (!Dcn.this.SETTINGS.recvMessagePartsFrom.contains(peerEntry.getKey())) {
              continue;
            }
            var peerSoc = peerEntry.getValue();
            var buf = ByteBuffer.allocate(msgSize);
            for (var read = 0; read < buf.capacity();) {
              read += peerSoc.read(buf);
            }
            var frame = Frame.deserialize(buf.flip());
            recvShares.put(peerEntry.getKey() + 1, frame.getData());
          }
        } catch (IOException e) {
          LOGGER.error("Error distributing message share ", e);
        }

        // if all received shares are null, no message was broadcast
        var nullMsg = true;
        var nullArray = new byte[msgDataSize];
        for (var sh : recvShares.values()) {
          nullMsg &= Arrays.equals(sh, nullArray);
        }
        if (!nullMsg) {
          toReceive.add(recvShares);
        }

      } // end while(true)
      peerSockets.values().forEach(t -> {
        try {
          t.close();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      });
    }

    private Map<Integer, byte[]> genSharedSecrets(int len) {
      final var sharedSecrets = new HashMap<Integer, byte[]>();
      for (var entry : randomNumberGenerators.entrySet()) {
        var generator = entry.getValue();
        var peerID = entry.getKey();
        var sharedBytes = new byte[len];

        generator.nextBytes(sharedBytes);
        sharedSecrets.put(peerID, sharedBytes);
      }
      return sharedSecrets;
    }
  }

  final static class Settings {
    private final Pair<Integer, Address> ownIP;
    private final Map<Integer, Address> peers;
    private final Set<Integer> sendMessagePartsTo;
    private final Set<Integer> recvMessagePartsFrom;

    private final int bytesPerRound;
    private final int roundInterval;
    private final int k;

    Settings(final Pair<Integer, Address> ownIP, final Map<Integer, Address> peers,
        final Set<Integer> sendMessagePartsTo, final Set<Integer> recvMessagePartsFrom, final int bytesPerRound,
        final int roundInterval, final int k) {
      this.ownIP = ownIP;
      this.peers = peers;
      this.sendMessagePartsTo = sendMessagePartsTo;
      this.recvMessagePartsFrom = recvMessagePartsFrom;

      this.bytesPerRound = bytesPerRound;
      this.roundInterval = roundInterval;
      this.k = k;
    }

    public static Settings fromYamlFile(final String path) throws FileNotFoundException, IOException {
      final YamlMapping cfg = Yaml.createYamlInput(new File(path)).readYamlMapping();
      final var stdPort = cfg.integer("stdPort");
      final var ownIP = withPort(cfg.string("ownIP"), stdPort);
      final var peers = cfg.yamlSequence("peers").values();
      final var nwMembersWithPort = peers.stream().map(ynode -> ((Scalar) ynode).value())
          .map(addr -> withPort(addr, stdPort)).collect(Collectors.toList());
      nwMembersWithPort.add(ownIP);

      // order peers and ownip
      Collections.sort(nwMembersWithPort);

      final var ownIndex = nwMembersWithPort.indexOf(ownIP);
      final var sortedPeers = IntStream.range(0, nwMembersWithPort.size()).boxed()
          .collect(Collectors.toMap(i -> i, i -> new Address(nwMembersWithPort.get(i))));
      final var ownAddr = sortedPeers.remove(ownIndex);

      final var bytesPerRound = cfg.integer("bytesPerRound");
      final var roundInterval = cfg.integer("roundInterval");
      final var k = cfg.integer("k");

      var msgFromKey = yamlToAddressList(cfg, "recvMessagePartsFrom", stdPort);
      final Set<Integer> recvMessagePartsFrom;
      if (msgFromKey == null) {
        recvMessagePartsFrom = new HashSet<>(sortedPeers.keySet());
      } else {
        recvMessagePartsFrom = msgFromKey.stream().map(addr -> Util.searchValue(sortedPeers, addr))
            .collect(Collectors.toSet());
      }

      var msgToKey = yamlToAddressList(cfg, "sendMessagePartsTo", stdPort);
      final Set<Integer> sendMessagePartsTo;

      if (msgToKey == null) {
        sendMessagePartsTo = new HashSet<>(sortedPeers.keySet());
      } else {
        sendMessagePartsTo = msgToKey.stream().map(addr -> Util.searchValue(sortedPeers, addr))
            .collect(Collectors.toSet());
      }
      return new Settings(new Pair<>(ownIndex, ownAddr), sortedPeers, sendMessagePartsTo, recvMessagePartsFrom,
          bytesPerRound, roundInterval, k);
    }

    private static String withPort(final String addr, final int defaultPort) {
      return addr.contains(":") ? addr : addr + ":" + defaultPort;
    }

    private static List<Address> yamlToAddressList(YamlMapping cfg, String key, int stdPort) {
      var seq = cfg.yamlSequence(key);
      if (seq == null) {
        return new ArrayList<>();
      }
      return seq.values().stream().map(ynode -> ((Scalar) ynode).value()).map(addr -> withPort(addr, stdPort))
          .map(Address::new).collect(Collectors.toList());
    }

    public Pair<Integer, Address> getOwnIP() {
      return ownIP;
    }

    public Map<Integer, Address> getPeers() {
      return peers;
    }

    public int getBytesPerRound() {
      return bytesPerRound;
    }

    public int getRoundInterval() {
      return roundInterval;
    }

    public int getK() {
      return k;
    }

    public Set<Integer> getSendMessagePartsTo() {
      return sendMessagePartsTo;
    }

    public Set<Integer> getRecvMessagePartsFrom() {
      return recvMessagePartsFrom;
    }

    @Override
    public String toString() {
      return "Settings [bytesPerRound=" + bytesPerRound + ", k=" + k + ", ownIP=" + ownIP + ", peers=" + peers
          + ", recvMessagePartsFrom=" + recvMessagePartsFrom + ", roundInterval=" + roundInterval
          + ", sendMessagePartsTo=" + sendMessagePartsTo + "]";
    }
  }
}