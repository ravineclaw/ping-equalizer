package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PrivateKeyReconstructor {
    
    private static final int XOR_KEY = 0x2A;
    private static final int[] ROTATION_KEYS = {
        0x5A, 0x3C, 0x7B, 0x1E, 0x4F, 0x9D, 0x2B, 0x8C,
        0x6E, 0xA1, 0x3F, 0xD2, 0x59, 0x87, 0xC3, 0x14
    };
    private static final String CHECKSUM_SALT = "::PINGEQUALIZER::";

    public static String reconstructPrivateKey() {
        try {
            String key = performReconstruction();
            if (key.isEmpty()) {
                return "";
            }
            if (!verifyChecksum(key)) {
                return "";
            }
            return key;
        } catch (Exception e) {
            return "";
        }
    }

    private static String performReconstruction() {
        String[] shards = PrivateKeyStorage.getShards();
        int[] permutation = PrivateKeyStorage.getPermutation();
        int realCount = Math.min(PrivateKeyStorage.getRealShardCount(), permutation.length);
        
        if (shards == null || shards.length == 0) {
            return "";
        }

        String[] recovered = new String[realCount];

        for (int i = 0; i < realCount; i++) {
            int storedIndex = i;
            int originalIndex = permutation[i];
            if (storedIndex < 0 || storedIndex >= shards.length) {
                continue;
            }
            if (originalIndex < 0 || originalIndex >= realCount) {
                continue;
            }
            String shard = shards[storedIndex];
            if (shard == null || shard.isEmpty()) {
                continue;
            }

            byte[] decodedBytes = Base64.getDecoder().decode(shard);
            String deobfuscated = deobfuscateShard(decodedBytes, originalIndex);
            recovered[originalIndex] = deobfuscated;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < recovered.length; i++) {
            if (recovered[i] != null) {
                result.append(recovered[i]);
            }
        }

        return result.toString();
    }

    private static String deobfuscateShard(byte[] shardBytes, int index) {
        if (shardBytes == null || shardBytes.length == 0) {
            return "";
        }

        char[] chars = new char[shardBytes.length];
        for (int i = 0; i < shardBytes.length; i++) {
            chars[i] = (char) (shardBytes[i] & 0xFF);
        }
        
        for (int i = 0; i < chars.length; i++) {
            int keyIndex = (i + index) % ROTATION_KEYS.length;
            chars[i] = (char) (chars[i] ^ ROTATION_KEYS[keyIndex]);
        }

        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ XOR_KEY);
        }
        
        return new String(chars);
    }

    public static boolean isValidKeyFormat(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return key.length() > 100;
    }

    public static String[] getRawShards() {
        return PrivateKeyStorage.getShards();
    }

    private static boolean verifyChecksum(String key) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(key.getBytes(StandardCharsets.UTF_8));
            md.update(CHECKSUM_SALT.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(PrivateKeyStorage.getExpectedChecksum());
        } catch (Exception e) {
            return false;
        }
    }
}
