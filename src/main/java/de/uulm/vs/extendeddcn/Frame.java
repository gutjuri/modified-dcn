package de.uulm.vs.extendeddcn;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.ByteBuffer;

public class Frame {

  private short seqNr;
  private FrameType type;
  private byte[] data;

  public Frame(short seqNr, FrameType type, byte[] data) {
    this.data = data;
    this.seqNr = seqNr;
    this.type = type;
  }

  public ByteBuffer serialize() {
    var buf = ByteBuffer.allocate(data.length + 4);
    buf.putShort(seqNr);
    buf.putShort(type.code);
    buf.put(data);
    buf.flip();
    return buf;
  }

  public static Frame deserialize(ByteBuffer buf) {
    ;
    var seqNr = buf.getShort();
    var type = FrameType.getByCode(buf.getShort());
    var data = new byte[buf.remaining()];
    buf.get(data);
    return new Frame(seqNr, type, data);
  }

  public static int msgSize(int bytesPerRound) {
    return bytesPerRound + 8;
  }

  public byte[] getData() {
    return data;
  }

  public short getSeqNr() {
    return seqNr;
  }

  public FrameType getType() {
    return type;
  }

}

enum FrameType {
  DCN_ROUND((short) 1), SHARE_FLOODING((short) 2);

  FrameType(short code) {
    this.code = code;
  }

  static FrameType getByCode(short code) {
    for (FrameType tp : FrameType.values()) {
      if (tp.code == code) {
        return tp;
      }
    }
    throw new IllegalArgumentException("Invalid FrameType code");
  }

  public final short code;
}
