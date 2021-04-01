package de.uulm.vs.extendeddcn.util;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class Util {
  /*
   * Converts a byte to hex digit and writes to the supplied buffer
   */
  public static void byte2hex(byte b, StringBuffer buf) {
    char[] hexChars = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C',
        'D', 'E', 'F' };
    int high = ((b & 0xf0) >> 4);
    int low = (b & 0x0f);
    buf.append(hexChars[high]);
    buf.append(hexChars[low]);
  }

  /*
   * Converts a byte array to hex string
   */
  public static String toHexString(byte[] block) {
    StringBuffer buf = new StringBuffer();
    int len = block.length;
    for (int i = 0; i < len; i++) {
      byte2hex(block[i], buf);
      if (i < len - 1) {
        buf.append(":");
      }
    }
    return buf.toString();
  }

  public static <K, V> K searchValue(Map<K, V> map, V value) {
    for (var entry : map.entrySet()) {
      if (value.equals(entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  public static <K> String mapToString(Map<K, byte[]> map) {
    return "{" + map.entrySet().stream()
        .map(e -> e.getKey() + "=" + Arrays.toString(e.getValue()))
        .collect(Collectors.joining(", ")) + "}";
  }

  public static boolean arrayPrefixEquals(byte[] a, byte[] b) {
    var len = Math.min(a.length, b.length);
    for (var i = 0; i < len; ++i) {
      if (a[i] != b[i]) {
        return false;
      }
    }
    return true;
  }
}