package net.ravenclaw.ravenclawspingequalizer.cryptography;

public class PrivateKeyPlaceholder {
    // The real key will be injected and split during the build process.
    // These are placeholders for the development environment.
    private static final String S01 = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDZ";
    private static final String S02 = "y8K5..."; // Placeholder
    private static final String S03 = "";
    private static final String S04 = "";
    private static final String S05 = "";
    private static final String S06 = "";
    private static final String S07 = "";
    private static final String S08 = "";
    private static final String S09 = "";
    private static final String S10 = "";
    private static final String S11 = "";
    private static final String S12 = "";
    private static final String S13 = "";
    private static final String S14 = "";
    private static final String S15 = "";
    private static final String S16 = "";
    private static final String S17 = "";
    private static final String S18 = "";
    private static final String S19 = "";
    private static final String S20 = "";

    public static String getPrivateKey() {
        StringBuilder sb = new StringBuilder();
        sb.append(deobfuscate(S01));
        sb.append(deobfuscate(S02));
        sb.append(deobfuscate(S03));
        sb.append(deobfuscate(S04));
        sb.append(deobfuscate(S05));
        sb.append(deobfuscate(S06));
        sb.append(deobfuscate(S07));
        sb.append(deobfuscate(S08));
        sb.append(deobfuscate(S09));
        sb.append(deobfuscate(S10));
        sb.append(deobfuscate(S11));
        sb.append(deobfuscate(S12));
        sb.append(deobfuscate(S13));
        sb.append(deobfuscate(S14));
        sb.append(deobfuscate(S15));
        sb.append(deobfuscate(S16));
        sb.append(deobfuscate(S17));
        sb.append(deobfuscate(S18));
        sb.append(deobfuscate(S19));
        sb.append(deobfuscate(S20));
        
        // If in dev environment and shards are empty, return the dev key
        if (sb.length() < 50) {
             return "DEV_ONLY_PRIVATE_KEY_1234567890";
        }
        
        return sb.toString();
    }

    private static String deobfuscate(String encrypted) {
        // Simple XOR deobfuscation
        // The build script should XOR the key fragments with the same key (e.g., 42)
        if (encrypted == null || encrypted.isEmpty()) return "";
        
        // For dev environment where placeholders are not encrypted, just return them
        if (encrypted.startsWith("MII") || encrypted.equals("y8K5...")) {
            return encrypted;
        }

        char[] chars = encrypted.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ 42);
        }
        return new String(chars);
    }
}
