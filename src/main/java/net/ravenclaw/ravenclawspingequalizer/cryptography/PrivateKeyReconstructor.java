package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PrivateKeyReconstructor {
    
    private static final int XOR_KEY = 0x2A;
    private static final int[] ROTATION_KEYS = {
        0x5A, 0x3C, 0x7B, 0x1E, 0x4F, 0x9D, 0x2B, 0x8C,
        0x6E, 0xA1, 0x3F, 0xD2, 0x59, 0x87, 0xC3, 0x14
    };

    public static String reconstructPrivateKey() {
        try {
            return performReconstruction();
        } catch (Exception e) {
            return "";
        }
    }

    private static String performReconstruction() {
        String[] shards = PrivateKeyStorage.getShards();
        
        if (shards == null || shards.length == 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < shards.length; i++) {
            String shard = shards[i];
            if (shard == null || shard.isEmpty()) {
                continue;
            }

            String decoded = new String(Base64.getDecoder().decode(shard), StandardCharsets.UTF_8);
            String deobfuscated = deobfuscateShard(decoded, i);
            result.append(deobfuscated);
        }

        return result.toString();
    }

    private static String deobfuscateShard(String shard, int index) {
        if (shard == null || shard.isEmpty()) {
            return "";
        }

        char[] chars = shard.toCharArray();
        
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ XOR_KEY);
        }
        
        for (int i = 0; i < chars.length; i++) {
            int keyIndex = (i + index) % ROTATION_KEYS.length;
            chars[i] = (char) (chars[i] ^ ROTATION_KEYS[keyIndex]);
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
}
