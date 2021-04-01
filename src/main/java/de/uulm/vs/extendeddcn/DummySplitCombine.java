package de.uulm.vs.extendeddcn;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A (n, 1)-secret-sharing-scheme. Essentially, this just copies the message n times.
 * 
 * @author Juri Dispan
 *
 */
public class DummySplitCombine extends SplitCombineStrategy {

  public DummySplitCombine(final int n) {
    super(n, 1);
  }

  @Override
  public Map<Integer, byte[]> split(final byte[] msg) {
    return IntStream.range(1, n + 1).boxed().collect(Collectors.toMap(i -> i, __ -> msg));
  }

  @Override
  public byte[] combine(final Map<Integer, byte[]> msgs) {
    return msgs.values().stream().findAny().get();
  }

}
