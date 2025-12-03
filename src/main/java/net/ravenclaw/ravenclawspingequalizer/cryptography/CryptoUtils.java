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
        try {
            ProtectionDomain pd = CryptoUtils.class.getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null) {
                throw new IllegalStateException();
            }

            Path codePath = Paths.get(pd.getCodeSource().getLocation().toURI()).toAbsolutePath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            if (Files.isRegularFile(codePath)) {
                try (InputStream in = Files.newInputStream(codePath); DigestInputStream din = new DigestInputStream(in, digest)) {
                    byte[] buffer = new byte[8192];
                    while (din.read(buffer) != -1) {}
                }
            } else if (Files.isDirectory(codePath)) {
                try (Stream<Path> stream = Files.walk(codePath)) {
                    stream.filter(Files::isRegularFile)
                          .sorted(Comparator.comparing(Path::toString))
                          .forEach(p -> {
                              Path rel = codePath.relativize(p);
                              byte[] pathBytes = rel.toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8);
                              digest.update(pathBytes);
                              digest.update((byte)0);
                              try (InputStream in = Files.newInputStream(p); DigestInputStream din = new DigestInputStream(in, digest)) {
                                  byte[] buf = new byte[8192];
                                  while (din.read(buf) != -1) {}
                              } catch (IOException e) {
                                  throw new RuntimeException(e);
                              }
                          });
                }
            } else {
                throw new IllegalStateException();
            }

            return digest.digest();
        } catch (URISyntaxException | NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] signPayload(byte[] payload, String privateKeyPemOrString) {
        try {
            try {
                PrivateKey pk = parsePrivateKeyFromPEM(privateKeyPemOrString);
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(pk);
                sig.update(payload);
                return sig.sign();
            } catch (Exception ex) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(privateKeyPemOrString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(payload);
                return digest.digest();
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String signPayload(byte[] payload, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payload);
            byte[] signature = sig.sign();
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static KeyPair generateSessionKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String calculateModHashHex() {
        byte[] hash = calculateModHash();
        return bytesToHex(hash);
    }

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

    public static PrivateKey getEmbeddedPrivateKey() {
        String pem = ModPrivateKey.getPrivateKey();
        try {
            return parsePrivateKeyFromPEM(pem);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean verifySignature(byte[] payload, byte[] signatureBytes, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(payload);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static PublicKey parsePublicKeyFromPEM(String pem) throws GeneralSecurityException {
        String normalized = pem.trim();
        if (normalized.contains("-----BEGIN")) {
            normalized = normalized.replaceAll("-----BEGIN [A-Z ]+-----", "");
            normalized = normalized.replaceAll("-----END [A-Z ]+-----", "");
        }
        normalized = normalized.replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    public static boolean verifyServerSignature(String data, String signatureBase64) {
        try {
            PublicKey serverKey = parsePublicKeyFromPEM(ServerKey.getPublicKey());
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(serverKey);
            sig.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }
}
