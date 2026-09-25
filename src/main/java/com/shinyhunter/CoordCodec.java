package com.shinyhunter;

import net.minecraft.core.BlockPos;

/**
 * Packs a block position into a fixed ten-letter code and back.
 *
 * <p>Used so a "beehive emptied" call-out carries the location without spelling out coordinates in
 * party chat. Every copy of the mod shares the constant key below, so any client can decode what
 * another sent; it's obfuscation for readability, not secrecy.
 *
 * <p><b>Layout.</b> X and Z take 18 bits each (±131,072) and Y takes 9 (−64 to 447, the full world
 * height), for 45 bits total. That fits in ten base-26 letters — 26¹⁰ ≈ 1.41×10¹⁴ against 2⁴⁵ ≈
 * 3.52×10¹³ — with room to spare. The key is XORed in before encoding so nearby positions don't
 * produce visibly similar codes.
 */
public final class CoordCodec {

    private static final int CODE_LENGTH = 10;

    /** Shared across every install — changing it breaks decoding of other players' messages. */
    private static final long KEY = 0x1B7A3F5C2D9EL & ((1L << 45) - 1);

    private static final int X_BITS = 18;
    private static final int Y_BITS = 9;
    private static final int Z_BITS = 18;

    private static final int X_OFFSET = 1 << (X_BITS - 1); // 131072
    private static final int Z_OFFSET = 1 << (Z_BITS - 1);
    private static final int Y_OFFSET = 64;                // world floor

    private static final int X_MAX = (1 << X_BITS) - 1;
    private static final int Y_MAX = (1 << Y_BITS) - 1;
    private static final int Z_MAX = (1 << Z_BITS) - 1;

    private CoordCodec() {
    }

    /** Encodes a position, or null if it falls outside the representable range. */
    public static String encode(BlockPos pos) {
        long x = (long) pos.getX() + X_OFFSET;
        long y = (long) pos.getY() + Y_OFFSET;
        long z = (long) pos.getZ() + Z_OFFSET;
        if (x < 0 || x > X_MAX || y < 0 || y > Y_MAX || z < 0 || z > Z_MAX) {
            return null;
        }

        long packed = (x << (Y_BITS + Z_BITS)) | (y << Z_BITS) | z;
        long value = packed ^ KEY;

        char[] out = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            out[i] = (char) ('A' + (int) (value % 26));
            value /= 26;
        }
        return new String(out);
    }

    /** Decodes a ten-letter code, or null if it isn't one this mod produced. */
    public static BlockPos decode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return null;
        }

        long value = 0;
        for (int i = 0; i < CODE_LENGTH; i++) {
            char c = Character.toUpperCase(code.charAt(i));
            if (c < 'A' || c > 'Z') {
                return null;
            }
            value = value * 26 + (c - 'A');
        }
        // Anything above the 45-bit space came from something else that happened to be ten letters.
        if (value < 0 || value >= (1L << 45)) {
            return null;
        }

        long packed = value ^ KEY;
        int z = (int) (packed & Z_MAX);
        int y = (int) ((packed >> Z_BITS) & Y_MAX);
        int x = (int) ((packed >> (Y_BITS + Z_BITS)) & X_MAX);
        return new BlockPos(x - X_OFFSET, y - Y_OFFSET, z - Z_OFFSET);
    }
}
