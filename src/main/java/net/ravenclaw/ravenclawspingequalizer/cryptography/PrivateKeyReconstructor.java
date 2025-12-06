package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reconstructs the private key from obfuscated distributed shards.
 * Uses XOR deobfuscation with dynamic key rotation for maximum security.
 */
public class PrivateKeyReconstructor {
    
    // Obfuscation key - can be derived from system properties for additional security
    private static final int XOR_KEY = 0x2A; // 42 in decimal, used for XOR operations
    private static final int[] ROTATION_KEYS = {
        0x5A, 0x3C, 0x7B, 0x1E, 0x4F, 0x9D, 0x2B, 0x8C,
        0x6E, 0xA1, 0x3F, 0xD2, 0x59, 0x87, 0xC3, 0x14
    };

    /**
     * Reconstructs the private key from storage shards.
     * @return Reconstructed private key string
     */
    public static String reconstructPrivateKey() {
        try {
            return performReconstruction();
        } catch (Exception e) {
            // Return empty string on reconstruction failure
            return "";
        }
    }

    /**
     * Internal reconstruction method with multi-layer deobfuscation.
     * @return Deobfuscated private key
     */
    private static String performReconstruction() {
        String[] shards = PrivateKeyStorage.getShards();
        
        if (shards == null || shards.length == 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        
        // Reconstruct each shard and append
        for (int i = 0; i < shards.length; i++) {
            String shard = shards[i];
            if (shard == null || shard.isEmpty()) {
                continue;
            }

            String decoded = new String(Base64.getDecoder().decode(shard), StandardCharsets.UTF_8);

            // Deobfuscate shard with rotation
            String deobfuscated = deobfuscateShard(decoded, i);
            result.append(deobfuscated);
        }

        return result.toString();
    }

    /**
     * Deobfuscates a single shard using XOR and rotation.
     * @param shard The obfuscated shard
     * @param index The index of the shard
     * @return Deobfuscated shard
     */
    private static String deobfuscateShard(String shard, int index) {
        if (shard == null || shard.isEmpty()) {
            return "";
        }

        char[] chars = shard.toCharArray();
        
        // XOR with primary key
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ XOR_KEY);
        }
        
        // XOR with rotation key for additional security
        for (int i = 0; i < chars.length; i++) {
            int keyIndex = (i + index) % ROTATION_KEYS.length;
            chars[i] = (char) (chars[i] ^ ROTATION_KEYS[keyIndex]);
        }
        
        return new String(chars);
    }

    /**
     * Validates the reconstructed key format.
     * @param key The key to validate
     * @return True if key appears valid
     */
    public static boolean isValidKeyFormat(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        
        // Basic validation - should contain BEGIN and END markers for PEM format
        // Or should contain base64-like content
        return key.length() > 100;
    }

    /**
     * Gets the obfuscated key as is (without reconstruction).
     * Useful for debugging obfuscation issues.
     * @return Array of raw shards
     */
    public static String[] getRawShards() {
        return PrivateKeyStorage.getShards();
    }
}
