package ai.neargo.shop.print.channel.storage;

import ai.neargo.shop.print.api.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shop.print.artifacts.provider",
        havingValue = "local",
        matchIfMissing = true)
public final class PrivateLocalArtifactStore
        implements PrintArtifactStorePort, PrintPrivateDownloadPort {
    private final Path root;
    private final String signingKey;
    private final String baseUrl;

    public PrivateLocalArtifactStore(
            @Value("${shop.print.artifacts.root:./data/print-private}") String root,
            @Value("${shop.print.artifacts.signing-key:}") String key,
            @Value("${shop.print.artifacts.download-base:/private/print-artifacts}") String base) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        signingKey = key;
        baseUrl = base;
    }

    private Path path(String key) {
        if (key == null
                || key.length() > 400
                || !key.matches("[A-Za-z0-9_/-]+[.]pdf")
                || key.contains("..")
                || key.startsWith("/")) throw new PrintFailure("print.invalidObjectKey");
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new PrintFailure("print.invalidObjectKey");
        return p;
    }

    public void put(PrivateArtifact artifact) {
        byte[] bytes = artifact.bytes();
        if (bytes.length > 20 * 1024 * 1024
                || bytes.length < 5
                || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")
                || !PrintHashes.sha256(bytes).equals(artifact.contentHash())
                || !"application/pdf".equals(artifact.contentType()))
            throw new PrintFailure("print.artifactIntegrityFailed");
        Path target = path(artifact.objectKey());
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.setPosixFilePermissions(
                        root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
            }
            if (Files.exists(target)) {
                if (!verify(artifact.objectKey()).sha256().equals(artifact.contentHash()))
                    throw new PrintFailure("print.artifactContentConflict");
                return;
            }
            temp = Files.createTempFile(target.getParent(), ".print-", ".tmp");
            Files.write(temp, bytes);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.createLink(target, temp);
            } catch (FileAlreadyExistsException duplicate) {
                if (!verify(artifact.objectKey()).sha256().equals(artifact.contentHash()))
                    throw new PrintFailure("print.artifactContentConflict");
            }
        } catch (java.io.IOException e) {
            throw new PrintFailure("print.artifactWriteFailed");
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (java.io.IOException ignored) {
                }
        }
    }

    public boolean exists(String key) {
        return Files.isRegularFile(path(key), LinkOption.NOFOLLOW_LINKS);
    }

    public ArtifactVerification verify(String key) {
        Path p = path(key);
        if (!exists(key)) return new ArtifactVerification(false, key, 0, null);
        try {
            long size = Files.size(p);
            if (size > 20 * 1024 * 1024) throw new PrintFailure("print.artifactTooLarge");
            byte[] bytes = Files.readAllBytes(p);
            return new ArtifactVerification(true, key, size, PrintHashes.sha256(bytes));
        } catch (java.io.IOException e) {
            throw new PrintFailure("print.artifactReadFailed");
        }
    }

    private String sign(String key, long expires) {
        try {
            byte[] secret = Base64.getDecoder().decode(signingKey);
            if (secret.length < 32) throw new IllegalArgumentException();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new PrintFailure("print.artifactSigningNotConfigured");
        }
    }

    public DownloadGrant signedDownload(String key, Duration ttl) {
        path(key);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(60)) > 0)
            throw new PrintFailure("print.invalidDownloadTtl");
        Instant expires = Instant.now().plus(ttl);
        String query =
                "key="
                        + URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "&expires="
                        + expires.getEpochSecond()
                        + "&signature="
                        + sign(key, expires.getEpochSecond());
        return new DownloadGrant(
                URI.create(baseUrl + "?" + query),
                expires,
                Path.of(key).getFileName().toString(),
                "application/pdf");
    }

    public byte[] read(String key, long expires, String signature) {
        long now = Instant.now().getEpochSecond();
        if (expires <= now
                || expires > now + 60
                || signature == null
                || !java.security.MessageDigest.isEqual(
                        sign(key, expires).getBytes(StandardCharsets.US_ASCII),
                        signature.getBytes(StandardCharsets.US_ASCII)))
            throw new PrintFailure("print.downloadExpired");
        if (!exists(key)) throw new PrintFailure("print.artifactNotFound");
        try {
            if (Files.size(path(key)) > 20 * 1024 * 1024)
                throw new PrintFailure("print.artifactTooLarge");
            return Files.readAllBytes(path(key));
        } catch (java.io.IOException e) {
            throw new PrintFailure("print.artifactReadFailed");
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (java.io.IOException e) {
            throw new PrintFailure("print.artifactDeleteFailed");
        }
    }
}
