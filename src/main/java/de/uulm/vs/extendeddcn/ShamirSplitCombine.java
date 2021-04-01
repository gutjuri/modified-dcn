package de.uulm.vs.extendeddcn;

import java.security.SecureRandom;
import java.util.Map;

import com.codahale.shamir.Scheme;

/**
 * 
 * @author Juri Dispan
 *
 */
public class ShamirSplitCombine extends SplitCombineStrategy {

  private final Scheme scheme;

  public ShamirSplitCombine(int n, int k) {
    super(n, k);
    scheme = new Scheme(new SecureRandom(), n, k);
  }

  @Override
  public Map<Integer, byte[]> split(byte[] msg) {
    return scheme.split(msg);
  }

  @Override
  public byte[] combine(Map<Integer, byte[]> msgs) {
    return scheme.join(msgs);
  }

}
