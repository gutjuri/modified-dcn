package de.uulm.vs.extendeddcn.util;

import java.util.Map;

public final class Pair<K, V> {
  private final K key;
  private final V value;

  public Pair(K key, V value) {
    this.key = key;
    this.value = value;
  }

  public K getKey() {
    return key;
  }

  public V getValue() {
    return value;
  }

  @Override
  public String toString() {
    return "Pair [key=" + key + ", value=" + value + "]";
  }

  public static <K, V> Pair<K, V> fromEntry(Map.Entry<K, V> e) {
    return new Pair<K, V>(e.getKey(), e.getValue());
  }

}