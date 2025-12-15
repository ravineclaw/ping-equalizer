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
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

public final class CryptoUtils {
    private CryptoUtils() {
    }

    private static final String SERVER_PUBLIC_KEY_PEM = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3K90vgcUTA7A+464Z/91
        +V5LNipBZ8vYXP5D7ctmh1xQMBMvw8aMim55LvxyuLb0tohdLYH0Aj6wRVmr3kIi
        BNyBXngL77n3Lqc4XX6i0vsCg8o3vCiu1XN+qlWxDDreORJglc+qQZ/jMxjm6SFr
        vgF1pkml2tqtIMBRTLEM3TRsAs0Ja+r86d7znL9TeZp6sjz+uMtQZVOY+edCE0GO
        zA1iTzpJCn7ufKP6QUDmxT4j8dtUy+5tIJFFLdFHS3e+WJkNqjQzqOlQNST+wsYx
        /Qld2afEdAmVjPMSs0/EMZOjRnex0IFA06af5z3gf8BX8sq5Nxlwpudgttq7JElc
        2wIDAQAB
        -----END PUBLIC KEY-----
        """;

    public static boolean verifyServerSignature(byte[] payload, byte[] signature) {
        try {
            PublicKey publicKey = getServerPublicKey();
            if (publicKey == null) {
                return false;
            }
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(payload);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static boolean verifyServerSignature(byte[] payload, String base64Signature) {
        try {
            byte[] signature = Base64.getDecoder().decode(base64Signature);
            return verifyServerSignature(payload, signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }


    public static boolean verifyServerSignature(String payload, String base64Signature) {
        return verifyServerSignature(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), base64Signature);
    }

    public static PublicKey getServerPublicKey() {
        try {
            return parsePublicKeyFromPEM(SERVER_PUBLIC_KEY_PEM);
        } catch (GeneralSecurityException e) {
            return null;
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
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    public static byte[] calculateModHash() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        try {
            Path preferred = resolveModPathFromLoader();
            if (preferred != null) {
                preferred = preferPackagedJarIfDirectory(preferred);
            }

            if (preferred == null || !Files.exists(preferred)) {
                ProtectionDomain pd = CryptoUtils.class.getProtectionDomain();
                if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null) {
                    throw new IllegalStateException("Unable to determine code location");
                }
                preferred = Paths.get(pd.getCodeSource().getLocation().toURI()).toAbsolutePath();
            }

            hashPath(preferred, digest);
            return digest.digest();
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
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

    private static Path resolveModPathFromLoader() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("ravenclawspingequalizer")
                    .flatMap(container -> container.getOrigin().getPaths().stream().findFirst())
                    .map(Path::toAbsolutePath)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path preferPackagedJarIfDirectory(Path originPath) {
        if (!Files.isDirectory(originPath)) {
            return originPath;
        }

        Path buildDir = findBuildDirectory(originPath);
        Path libsDir = buildDir != null ? buildDir.resolve("libs") : null;

        Path candidate = libsDir != null ? pickPreferredJar(libsDir) : null;
        return candidate != null ? candidate : originPath;
    }

    private static Path findBuildDirectory(Path start) {
        Path current = start.toAbsolutePath();
        while (current != null) {
            if ("build".equalsIgnoreCase(current.getFileName().toString())) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path pickPreferredJar(Path libsDir) {
        if (!Files.isDirectory(libsDir)) {
            return null;
        }

        String version = resolveModVersion();
        List<String> preferredNames = new ArrayList<>();
        if (version != null && !version.isBlank()) {
            preferredNames.add("ravenclawspingequalizer-" + version + "-obf.jar");
            preferredNames.add("ravenclawspingequalizer-" + version + "-proguard.jar");
            preferredNames.add("ravenclawspingequalizer-" + version + ".jar");
        }

        for (String name : preferredNames) {
            Path candidate = libsDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        try (Stream<Path> stream = Files.list(libsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String resolveModVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("ravenclawspingequalizer")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void hashPath(Path target, MessageDigest digest) throws IOException {
        if (Files.isRegularFile(target)) {
            try (InputStream in = Files.newInputStream(target);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[8192];
                while (din.read(buffer) != -1) {
                }
            }
            return;
        }

        if (Files.isDirectory(target)) {
            Path codePath = target;
            try (Stream<Path> stream = Files.walk(codePath)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(p -> {
                            Path rel = codePath.relativize(p);
                            byte[] pathBytes = rel.toString().replace('\\', '/')
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            return;
        }

        throw new IllegalStateException("Unexpected code location type");
    }
}
