package ai.neargo.shop.invoice.api.publicaccess;

import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.invoice.service.InvoiceDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@ConditionalOnProperty(name = "shop.invoice.delivery.enabled", havingValue = "true")
@RestController
@RequestMapping("/public/invoice-deliveries")
public class InvoiceDeliveryAccessController {
    private final InvoiceDeliveryService deliveries;
    private final ByteArrayResource page;

    public InvoiceDeliveryAccessController(InvoiceDeliveryService deliveries) throws IOException {
        this.deliveries = deliveries;
        this.page = new ByteArrayResource(new ClassPathResource("invoice/delivery-access.html").getContentAsByteArray());
    }

    @GetMapping(value = "/open", produces = "text/html;charset=UTF-8")
    public ResponseEntity<ByteArrayResource> open() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        "default-src 'none'; connect-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                .body(page);
    }

    @PostMapping("/access-challenges")
    public ResponseEntity<Map<String, Boolean>> challenge(@Valid @RequestBody ChallengeReq request) {
        deliveries.challenge(request.token(), ip());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("sent", true));
    }

    @PostMapping("/accesses")
    public ResponseEntity<Map<String, String>> redeem(@Valid @RequestBody AccessReq request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("url", deliveries.download(request.token(), request.code(), ip())));
    }

    private static String ip() {
        var meta = RequestMetaContext.current();
        return meta == null ? null : meta.ip();
    }

    public record ChallengeReq(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "ChallengeReq[redacted]"; }
    }

    public record AccessReq(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
        @Override public String toString() { return "AccessReq[redacted]"; }
    }
}
