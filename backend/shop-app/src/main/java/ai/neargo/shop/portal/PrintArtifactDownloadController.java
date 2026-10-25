package ai.neargo.shop.portal;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.print.api.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@ai.neargo.shop.portal.PrintEndpoint
@RestController
@Profile("api")
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class PrintArtifactDownloadController {
    private final PrintPrivateDownloadPort artifacts;

    public PrintArtifactDownloadController(PrintPrivateDownloadPort a) {
        artifacts = a;
    }

    @GetMapping("/private/print-artifacts")
    public ResponseEntity<byte[]> download(
            @RequestParam String key, @RequestParam long expires, @RequestParam String signature) {
        byte[] bytes = artifacts.read(key, expires, signature);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(java.nio.file.Path.of(key).getFileName().toString())
                                .build()
                                .toString())
                .body(bytes);
    }
}
