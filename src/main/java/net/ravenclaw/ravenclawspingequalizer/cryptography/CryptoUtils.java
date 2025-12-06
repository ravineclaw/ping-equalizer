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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProtectionDomain;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

public final class CryptoUtils {
    private CryptoUtils() {
    }

    public static byte[] calculateModHash() {
        try {
            ProtectionDomain pd = CryptoUtils.class.getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null) {
                throw new IllegalStateException("Unable to determine code location");
            }

            Path codePath = Paths.get(pd.getCodeSource().getLocation().toURI()).toAbsolutePath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            if (Files.isRegularFile(codePath)) {
                try (InputStream in = Files.newInputStream(codePath);
                     DigestInputStream din = new DigestInputStream(in, digest)) {
                    byte[] buffer = new byte[8192];
                    while (din.read(buffer) != -1) {
                    }
                }
            } else if (Files.isDirectory(codePath)) {
                try (Stream<Path> stream = Files.walk(codePath)) {
                    stream.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .forEach(p -> {
                                Path rel = codePath.relativize(p);
                                byte[] pathBytes = rel.toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                digest.update(pathBytes);
                                digest.update((byte) 0);
                                try (InputStream in = Files.newInputStream(p);
                                     DigestInputStream din = new DigestInputStream(in, digest)) {
                                    byte[] buf = new byte[8192];
                                    while (din.read(buf) != -1) {
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            } else {
                throw new IllegalStateException("Unexpected code location type");
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
            } catch (GeneralSecurityException ex) {
                // Fallback to digest
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
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
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

    public static PrivateKey getEmbeddedPrivateKey() {
        String pem = PrivateKeyReconstructor.reconstructPrivateKey();
        try {
            return parsePrivateKeyFromPEM(pem);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
