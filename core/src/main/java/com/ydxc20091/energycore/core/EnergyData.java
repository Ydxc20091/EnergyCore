package com.ydxc20091.energycore.core;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Versioned storage encoding. Invalid and unsupported values retain their original bytes. */
public final class EnergyData {
    private static final int MAGIC = 0x454E434F;
    private static final short VERSION = 1;
    private static final int LENGTH = Integer.BYTES + Short.BYTES + Long.BYTES + Integer.BYTES;
    private EnergyData() {}
    public enum Status { ABSENT, PRESENT, UNKNOWN_VERSION, INVALID }
    public record DecodeResult(Status status, long energy, byte[] raw, String message) {
        public DecodeResult { raw = raw == null ? null : raw.clone(); }
        @Override public byte[] raw() { return raw == null ? null : raw.clone(); }
        public boolean writable() { return status == Status.ABSENT || status == Status.PRESENT; }
    }
    public static byte[] encode(long energy) {
        if (energy < 0) throw new IllegalArgumentException("Negative energy amount");
        byte[] bytes = new byte[LENGTH];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(MAGIC).putShort(VERSION).putLong(energy);
        CRC32 checksum = new CRC32(); checksum.update(bytes, 0, LENGTH - Integer.BYTES);
        buffer.putInt((int) checksum.getValue());
        return bytes;
    }
    public static DecodeResult decode(byte[] bytes) {
        if (bytes == null) return new DecodeResult(Status.ABSENT, 0, null, "No stored data");
        if (bytes.length < Integer.BYTES + Short.BYTES) return invalid(bytes, "Truncated energy header");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) return invalid(bytes, "Unknown energy signature");
        short version = buffer.getShort();
        if (version != VERSION) return new DecodeResult(Status.UNKNOWN_VERSION, 0, bytes, "Unsupported energy version " + Short.toUnsignedInt(version));
        if (bytes.length != LENGTH) return invalid(bytes, "Invalid energy data length");
        long energy = buffer.getLong();
        int expected = buffer.getInt();
        CRC32 checksum = new CRC32(); checksum.update(bytes, 0, LENGTH - Integer.BYTES);
        if ((int) checksum.getValue() != expected) return invalid(bytes, "Energy checksum mismatch");
        if (energy < 0) return invalid(bytes, "Negative stored energy");
        return new DecodeResult(Status.PRESENT, energy, bytes, "Decoded");
    }
    private static DecodeResult invalid(byte[] raw, String message) { return new DecodeResult(Status.INVALID, 0, raw, message); }
}
