package de.uulm.vs.extendeddcn.util;

/**
 * Represents a pair of hostname and port.
 */
public final class Address implements Comparable<Address> {
  private final String host;
  private final int port;

  public Address(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public Address(String hostport) {
    var sStr = hostport.split(":");
    this.host = sStr[0];
    this.port = Integer.parseInt(sStr[1]);
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  @Override
  public String toString() {
    return "Address [host=" + host + ", port=" + port + "]";
  }

  @Override
  public int compareTo(Address other) {
    if (this.host.compareTo(other.host) != 0) {
      return this.host.compareTo(other.host);
    }
    return Integer.compare(this.port, other.port);
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((host == null) ? 0 : host.hashCode());
    result = prime * result + port;
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Address other = (Address) obj;
    if (host == null) {
      if (other.host != null)
        return false;
    } else if (!host.equals(other.host))
      return false;
    if (port != other.port)
      return false;
    return true;
  }

}