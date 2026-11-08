package com.neko.music.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * QQ 音乐 QRC 解密：hex → QQ 私有 DES-like 分组密码 → zlib（兼容 raw deflate / gzip）。
 *
 * <p>QQ 的算法不是标准 DES/3DES，不能直接交给 JCE。这里移植 AMLL / LDDC 的
 * 非标准位序实现，仅用于 QRC 歌词。</p>
 */
public final class QqQrcCrypto {

    private static final byte[] KEY = "!@#)(*$%123ZXC!@!@#)(NHL"
            .getBytes(StandardCharsets.UTF_8);
    private static final int[] KEY_PERM_C = {
            56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
            58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    };
    private static final int[] KEY_PERM_D = {
            62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
            60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    };
    private static final int[] KEY_COMPRESSION = {
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
            25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
            50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    };
    private static final int[] KEY_RND_SHIFT = {
            1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1
    };
    private static final int[] IP_RULE = {
            34, 42, 50, 58, 2, 10, 18, 26, 36, 44, 52, 60, 4, 12, 20, 28,
            38, 46, 54, 62, 6, 14, 22, 30, 40, 48, 56, 64, 8, 16, 24, 32,
            33, 41, 49, 57, 1, 9, 17, 25, 35, 43, 51, 59, 3, 11, 19, 27,
            37, 45, 53, 61, 5, 13, 21, 29, 39, 47, 55, 63, 7, 15, 23, 31
    };
    private static final int[] INV_IP_RULE = {
            37, 5, 45, 13, 53, 21, 61, 29, 38, 6, 46, 14, 54, 22, 62, 30,
            39, 7, 47, 15, 55, 23, 63, 31, 40, 8, 48, 16, 56, 24, 64, 32,
            33, 1, 41, 9, 49, 17, 57, 25, 34, 2, 42, 10, 50, 18, 58, 26,
            35, 3, 43, 11, 51, 19, 59, 27, 36, 4, 44, 12, 52, 20, 60, 28
    };
    private static final int[] E_BOX = {
            32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
            12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21,
            22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1
    };
    private static final int[] P_BOX = {
            16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
            2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25
    };
    private static final int[][] S_BOXES = {
            {14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                    0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                    4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                    15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13},
            {15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                    3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
                    0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                    13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9},
            {10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                    13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                    13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                    1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12},
            {7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                    13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                    10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                    3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14},
            {2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                    14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                    4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                    11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3},
            {12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                    10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                    9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                    4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13},
            {4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                    13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                    1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                    6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12},
            {13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                    1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                    7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                    2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11}
    };

    private QqQrcCrypto() {
    }

    public static String decrypt(String encryptedHex) throws IOException {
        if (encryptedHex == null || encryptedHex.isBlank()) {
            return "";
        }
        byte[] encrypted;
        try {
            encrypted = HexFormat.of().parseHex(
                    encryptedHex.replaceAll("\\s+", "").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IOException("QQ QRC 不是合法 hex", e);
        }
        if (encrypted.length % 8 != 0) {
            throw new IOException("QQ QRC 密文长度不是 8 字节整数倍");
        }

        int[][] schedules = new int[][]{
                keySchedule(KEY, 16, false),
                keySchedule(KEY, 8, true),
                keySchedule(KEY, 0, false)
        };
        byte[] decrypted = new byte[encrypted.length];
        byte[] block = new byte[8];
        for (int offset = 0; offset < encrypted.length; offset += 8) {
            System.arraycopy(encrypted, offset, block, 0, 8);
            for (int[] schedule : schedules) {
                block = cryptBlock(block, schedule);
            }
            System.arraycopy(block, 0, decrypted, offset, 8);
        }
        return decodePayload(decrypted);
    }

    static String decodePayload(byte[] decrypted) throws IOException {
        byte[] inflated = inflate(decrypted, false);
        if (inflated == null) {
            inflated = inflate(decrypted, true);
        }
        if (inflated == null) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(decrypted))) {
                inflated = in.readAllBytes();
            } catch (IOException ignored) {
                // 也可能本身未压缩
            }
        }
        String text = new String(
                inflated == null ? decrypted : inflated,
                StandardCharsets.UTF_8
        );
        if (text.contains("[") || text.contains("<")) {
            return text;
        }
        throw new IOException("QQ QRC 解压结果不是歌词");
    }

    private static int[] keySchedule(byte[] key, int offset, boolean encrypt) {
        long c = permuteKey(key, offset, KEY_PERM_C) << 4;
        long d = permuteKey(key, offset, KEY_PERM_D) << 4;
        int[] schedule = new int[32];
        for (int round = 0; round < 16; round++) {
            c = rotate28(c, KEY_RND_SHIFT[round]);
            d = rotate28(d, KEY_RND_SHIFT[round]);
            int target = encrypt ? round : 15 - round;
            long subKey = 0;
            for (int i = 0; i < KEY_COMPRESSION.length; i++) {
                int pos = KEY_COMPRESSION[i];
                long bit = pos < 28
                        ? (c >>> (31 - pos)) & 1L
                        : (d >>> (31 - (pos - 27))) & 1L;
                if (bit != 0) {
                    subKey |= 1L << (47 - i);
                }
            }
            schedule[target * 2] = (int) ((subKey >>> 24) & 0xFFFFFF);
            schedule[target * 2 + 1] = (int) (subKey & 0xFFFFFF);
        }
        return schedule;
    }

    private static long permuteKey(byte[] key, int offset, int[] table) {
        long output = 0;
        for (int i = 0; i < table.length; i++) {
            int pos = table[i];
            int wordIndex = pos >>> 5;
            int bitInWord = pos & 31;
            int byteInWord = bitInWord >>> 3;
            int bitInByte = bitInWord & 7;
            int byteIndex = offset + wordIndex * 4 + 3 - byteInWord;
            int bit = (key[byteIndex] >>> (7 - bitInByte)) & 1;
            if (bit != 0) {
                output |= 1L << (table.length - 1 - i);
            }
        }
        return output;
    }

    private static long rotate28(long value, int amount) {
        long masked = value & 0xFFFFFFF0L;
        return ((masked << amount) | (masked >>> (28 - amount))) & 0xFFFFFFF0L;
    }

    private static byte[] cryptBlock(byte[] input, int[] schedule) {
        long block = 0;
        for (byte b : input) {
            block = (block << 8) | (b & 0xFFL);
        }
        long permuted = permute64(block, IP_RULE);
        long left = (permuted >>> 32) & 0xFFFFFFFFL;
        long right = permuted & 0xFFFFFFFFL;
        for (int round = 0; round < 15; round++) {
            long next = (left ^ fFunction(right, schedule[round * 2], schedule[round * 2 + 1]))
                    & 0xFFFFFFFFL;
            left = right;
            right = next;
        }
        left = (left ^ fFunction(right, schedule[30], schedule[31])) & 0xFFFFFFFFL;
        long swapped = (left << 32) | right;
        long output = permute64(swapped, INV_IP_RULE);
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (output & 0xFF);
            output >>>= 8;
        }
        return result;
    }

    private static long permute64(long input, int[] rule) {
        long output = 0;
        for (int i = 0; i < 64; i++) {
            int sourceBit = 64 - rule[i];
            if (((input >>> sourceBit) & 1L) != 0) {
                output |= 1L << (63 - i);
            }
        }
        return output;
    }

    private static int fFunction(long state, int keyHigh24, int keyLow24) {
        int eboxHigh = 0;
        int eboxLow = 0;
        for (int i = 0; i < 48; i++) {
            int bit = (int) ((state >>> (32 - E_BOX[i])) & 1L);
            if (bit != 0) {
                if (i < 24) {
                    eboxHigh |= 1 << (23 - i);
                } else {
                    eboxLow |= 1 << (47 - i);
                }
            }
        }
        int xorHigh = eboxHigh ^ keyHigh24;
        int xorLow = eboxLow ^ keyLow24;
        int substituted = 0;
        for (int box = 0; box < 8; box++) {
            int sixBits = box < 4
                    ? (xorHigh >>> (18 - box * 6)) & 0x3F
                    : (xorLow >>> (42 - box * 6)) & 0x3F;
            int index = (sixBits & 0x20) | ((sixBits & 0x1F) >>> 1)
                    | ((sixBits & 1) << 4);
            int nibble = S_BOXES[box][index];
            substituted |= nibble << (28 - box * 4);
        }
        int output = 0;
        for (int i = 0; i < 32; i++) {
            int sourceMask = 1 << (32 - P_BOX[i]);
            if ((substituted & sourceMask) != 0) {
                output |= 1 << (31 - i);
            }
        }
        return output;
    }

    private static byte[] inflate(byte[] input, boolean nowrap) {
        try (InflaterInputStream in = new InflaterInputStream(
                new ByteArrayInputStream(input), new Inflater(nowrap))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, input.length * 3));
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException ignored) {
            return null;
        }
    }
}
