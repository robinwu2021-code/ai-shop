package ai.neargo.shop.print.channel.artifact;

import ai.neargo.shop.print.api.*;

import com.qcloud.cos.*;
import com.qcloud.cos.auth.*;
import com.qcloud.cos.http.*;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;

@Component
@ConditionalOnProperty(name = "shop.print.artifacts.provider", havingValue = "cos")
public final class CosPrintArtifactStore
        implements PrintArtifactStorePort, PrintPrivateDownloadPort, AutoCloseable {
    private final COSClient cos;
    private final String bucket;

    public CosPrintArtifactStore(
            @Value("${shop.cos.secret-id:}") String id,
            @Value("${shop.cos.secret-key:}") String secret,
            @Value("${shop.cos.region:}") String region,
            @Value("${shop.print.artifacts.cos.bucket:}") String bucket) {
        if (id.isBlank() || secret.isBlank() || region.isBlank() || bucket.isBlank())
            throw new IllegalStateException("PRINT_PRIVATE_COS_NOT_CONFIGURED");
        this.bucket = bucket;
        var config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        cos = new COSClient(new BasicCOSCredentials(id, secret), config);
    }

    private String key(String key) {
        if (key == null
                || key.length() > 400
                || !key.matches("[A-Za-z0-9_/-]+[.]pdf")
                || key.contains("..")
                || key.startsWith("/")) throw new PrintFailure("print.invalidObjectKey");
        return "print-private/" + key;
    }

    public void put(PrivateArtifact a) {
        byte[] bytes = a.bytes();
        if (bytes.length > 20 * 1024 * 1024
                || bytes.length < 5
                || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("%PDF-")
                || !PrintHashes.sha256(bytes).equals(a.contentHash()))
            throw new PrintFailure("print.artifactIntegrityFailed");
        var existing = verify(a.objectKey());
        if (existing.exists()) {
            if (!a.contentHash().equals(existing.sha256()))
                throw new PrintFailure("print.artifactContentConflict");
            return;
        }
        var metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType("application/pdf");
        metadata.setContentDisposition("attachment; filename=receipt.pdf");
        var request =
                new PutObjectRequest(
                        bucket, key(a.objectKey()), new ByteArrayInputStream(bytes), metadata);
        request.setCannedAcl(CannedAccessControlList.Private);
        request.putCustomRequestHeader("x-cos-forbid-overwrite", "true");
        try {
            cos.putObject(request);
        } catch (com.qcloud.cos.exception.CosServiceException e) {
            if (e.getStatusCode() != 409) throw e;
            var other = verify(a.objectKey());
            if (!other.exists() || !a.contentHash().equals(other.sha256()))
                throw new PrintFailure("print.artifactContentConflict");
        }
    }

    public boolean exists(String key) {
        return cos.doesObjectExist(bucket, key(key));
    }

    public ArtifactVerification verify(String key) {
        if (!exists(key)) return new ArtifactVerification(false, key, 0, null);
        var object = cos.getObject(bucket, key(key));
        try (var in = object.getObjectContent()) {
            if (object.getObjectMetadata().getContentLength() > 20 * 1024 * 1024)
                throw new PrintFailure("print.artifactTooLarge");
            byte[] bytes = in.readNBytes(20 * 1024 * 1024 + 1);
            if (bytes.length > 20 * 1024 * 1024) throw new PrintFailure("print.artifactTooLarge");
            return new ArtifactVerification(true, key, bytes.length, PrintHashes.sha256(bytes));
        } catch (IOException e) {
            throw new PrintFailure("print.artifactReadFailed");
        }
    }

    public DownloadGrant signedDownload(String key, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(60)) > 0)
            throw new PrintFailure("print.invalidDownloadTtl");
        Instant expires = Instant.now().plus(ttl);
        var request = new GeneratePresignedUrlRequest(bucket, key(key), HttpMethodName.GET);
        request.setExpiration(Date.from(expires));
        request.setResponseHeaders(
                new ResponseHeaderOverrides()
                        .withContentType("application/pdf")
                        .withContentDisposition("attachment; filename=receipt.pdf")
                        .withCacheControl("no-store"));
        return new DownloadGrant(
                URI.create(cos.generatePresignedUrl(request).toString()),
                expires,
                java.nio.file.Path.of(key).getFileName().toString(),
                "application/pdf");
    }

    public void delete(String key) {
        cos.deleteObject(bucket, key(key));
    }

    /**
     * COS uses its native signed URL; this local signed-file endpoint never proxies a public
     * object.
     */
    public byte[] read(String key, long expires, String signature) {
        throw new PrintFailure("print.downloadGrantInvalid");
    }

    @Override
    public void close() {
        cos.shutdown();
    }
}
