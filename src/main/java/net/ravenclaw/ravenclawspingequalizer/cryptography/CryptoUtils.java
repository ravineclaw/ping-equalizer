package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.ProtectionDomain;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;
 

public class CryptoUtils {
    public static byte[] calculateModHash() {
        // calculate the hash of the current mod based on it's path and contents
        try {
            // find location of this class's code source (jar file or classes directory)
            ProtectionDomain pd = CryptoUtils.class.getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null) {
                throw new IllegalStateException("Unable to determine code source location for mod");
            }

            Path codePath = Paths.get(pd.getCodeSource().getLocation().toURI()).toAbsolutePath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            if (Files.isRegularFile(codePath)) {
                // If running from a jar, hash the jar bytes
                try (InputStream in = Files.newInputStream(codePath); DigestInputStream din = new DigestInputStream(in, digest)) {
                    byte[] buffer = new byte[8192];
                    while (din.read(buffer) != -1) {
                        // reading through DigestInputStream updates the digest
                    }
                }
            } else if (Files.isDirectory(codePath)) {
                // If running from classes directory (dev), walk files in deterministic order
                try (Stream<Path> stream = Files.walk(codePath)) {
                    stream.filter(Files::isRegularFile)
                          .sorted(Comparator.comparing(Path::toString))
                          .forEach(p -> {
                              // include the relative path bytes so structure affects the hash
                              Path rel = codePath.relativize(p);
                              byte[] pathBytes = rel.toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8);
                              digest.update(pathBytes);
                              digest.update((byte)0);
                              try (InputStream in = Files.newInputStream(p); DigestInputStream din = new DigestInputStream(in, digest)) {
                                  byte[] buf = new byte[8192];
                                  while (din.read(buf) != -1) {
                                      // digest updated by DigestInputStream
                                  }
                              } catch (IOException e) {
                                  throw new RuntimeException("Failed to read file while hashing: " + p, e);
                              }
                          });
                }
            } else {
                throw new IllegalStateException("Code source path is neither a file nor a directory: " + codePath);
            }

            return digest.digest();
        } catch (URISyntaxException | NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to calculate mod hash", e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Sign using an embedded/private key string (PEM). If parsing fails, fall back to
     * the deterministic SHA-256(privateKeyString || payload) digest to preserve
     * existing behavior for dev builds.
     */
    public static byte[] signPayload(byte[] payload, String privateKeyPemOrString) {
        try {
            try {
                PrivateKey pk = parsePrivateKeyFromPEM(privateKeyPemOrString);
                // sign and return raw signature bytes
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(pk);
                sig.update(payload);
                return sig.sign();
            } catch (Exception ex) {
                // fallback to deterministic digest used previously (dev fallback)
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(privateKeyPemOrString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(payload);
                return digest.digest();
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to sign payload", e);
        }
    }

    /**
     * Sign payload with a PrivateKey and return the signature as Base64 string.
     */
    public static String signPayload(byte[] payload, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payload);
            byte[] signature = sig.sign();
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign payload with private key", e);
        }
    }

    public static KeyPair generateSessionKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate session key pair", e);
        }
    }

    /**
     * Convenience: returns mod hash as hex string.
     */
    public static String calculateModHashHex() {
        byte[] hash = calculateModHash();
        return bytesToHex(hash);
    }

    /**
     * Parse a PKCS#8 PEM-formatted private key (or raw base64 PKCS#8) into a PrivateKey.
     */
    public static PrivateKey parsePrivateKeyFromPEM(String pem) throws GeneralSecurityException {
        String normalized = pem.trim();
        if (normalized.contains("-----BEGIN")) {
            normalized = normalized.replaceAll("-----BEGIN [A-Z ]+-----", "");
            normalized = normalized.replaceAll("-----END [A-Z ]+-----", "");
        }
        normalized = normalized.replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    /**
     * Serialize a public key to PEM (-----BEGIN PUBLIC KEY----- ...).
     */
    public static String serializePublicKey(PublicKey pub) {
        byte[] encoded = pub.getEncoded();
        String b64 = Base64.getEncoder().encodeToString(encoded);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PUBLIC KEY-----\n");
        int idx = 0;
        while (idx < b64.length()) {
            int end = Math.min(idx + 64, b64.length());
            sb.append(b64, idx, end).append('\n');
            idx = end;
        }
        sb.append("-----END PUBLIC KEY-----\n");
        return sb.toString();
    }

    /**
     * Attempt to load the embedded private key from `PrivateKey.getPrivateKey()`.
     * Returns null if the embedded string cannot be parsed as a PKCS#8 PEM.
     */
    public static PrivateKey getEmbeddedPrivateKey() {
        String pem = net.ravenclaw.ravenclawspingequalizer.cryptography.PrivateKey.getPrivateKey();
        try {
            return parsePrivateKeyFromPEM(pem);
        } catch (Exception e) {
            // Not a valid PEM/PKCS8 — return null so callers can handle dev fallback
            return null;
        }
    }

}
