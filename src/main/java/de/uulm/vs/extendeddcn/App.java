/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package de.uulm.vs.extendeddcn;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.uulm.vs.extendeddcn.Dcn.Settings;

/**
 * Reads from the config file specified by environment variable EDCN_CONFIG and
 * performs a benchmark, broadcasting and measuring the time taken.
 */
public class App {

  private static Logger LOGGER = LogManager.getLogger();

  public static void main(final String[] args) throws IOException, InterruptedException {
    var envs = System.getenv();
    Settings settings = null;
    if (envs.containsKey("EDCN_CONFIG")) {
      try {
        settings = Settings.fromYamlFile(envs.get("EDCN_CONFIG"));
      } catch (final FileNotFoundException e) {
        System.err.println("Config file not found!");
      } catch (final IOException e) {
        System.err.println("IO Error!" + e.getMessage());
      }
    } else {
      System.err.println("No config file specified (Use environment variable EDCN_CONFIG)");
      System.exit(1);
    }
    LOGGER.info("Active settings: " + settings + "; Starting...");

    // init DCN
    Dcn dcn = new Dcn(settings);

    // start broadcasting
    var t_start = System.currentTimeMillis();

    var simulatedBytes = testWithoutReassembly(dcn, settings);
    //var simulatedBytes = testWithReassembly(dcn, settings);

    var t_end = System.currentTimeMillis();
    Thread.sleep(1000);
    dcn.shutdown();
    LOGGER.info("Received " + (simulatedBytes * 0.001) + "kb in " + (t_end - t_start) + "ms.");
  }

  private static int testWithoutReassembly(Dcn dcn, Settings settings) throws IOException, InterruptedException {
    int simulatedKBytes = 100;
    var msg = new byte[1024];
    Arrays.fill(msg, (byte) 1);
    if (settings.getOwnIP().getKey() == 0) {
      for (var i = 0; i < simulatedKBytes; i++) {
        dcn.broadcast(msg);
      }
      LOGGER.info("Finished broadcasting");
    }
    for (var received = 0; received < simulatedKBytes * 1024;) {
      var sharesRec = dcn.recvShares();
      var recShareSize = 0;
      for (byte[] b : sharesRec.values()) {
        recShareSize = b.length;
      }
      received += recShareSize;
      LOGGER.info("Received shares of size " + recShareSize);
    }
    return simulatedKBytes * 1000;
  }

  private static int testWithReassembly(Dcn dcn, Settings settings) throws IOException, InterruptedException {
    var msg = "Hello World!".getBytes();
    if (settings.getOwnIP().getKey() == 0) {
      dcn.broadcast(msg);
      LOGGER.info("Finished broadcasting");
    }
    var buf = new byte[msg.length];
    dcn.recv(buf);

    LOGGER.info("Received message \"" + new String(buf) + "\"");
    return msg.length;
  }

}
