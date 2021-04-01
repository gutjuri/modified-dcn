package de.uulm.vs.extendeddcn.util;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AddressTest {
  @Test
  public void testCompareTo() {
    Address a1 = new Address("127.0.0.1:1337");
    Address a2 = new Address("127.0.0.1:1338");
    Address a3 = new Address("127.0.0.1:1339");

    assertTrue(a1.compareTo(a1) == 0);
    assertTrue(a1.compareTo(a2) < 0);
    assertTrue(a1.compareTo(a3) < 0);

    assertTrue(a2.compareTo(a1) > 0);
    assertTrue(a2.compareTo(a2) == 0);
    assertTrue(a2.compareTo(a3) < 0);

    assertTrue(a3.compareTo(a1) > 0);
    assertTrue(a3.compareTo(a2) > 0);
    assertTrue(a3.compareTo(a3) == 0);

  }
}
