package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelCertificateVaultPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Private original-owner capture verifies service authentication and actual merchant owner before
 * sealing; no raw journal is used.
 */
@RestController
public class InternalChannelCertificateCaptureController {
    private final String token;
    private final ChannelCertificateVaultPort vault;

    public InternalChannelCertificateCaptureController(
            String token, ChannelCertificateVaultPort vault) {
        this.token = token;
        this.vault = vault;
    }

    public record Handle(String controlledCertificateRef) {}

    @PostMapping(
            value = "/internal/commerce/certificates/capture",
            consumes = "application/octet-stream")
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String provided,
            @RequestHeader("X-Entity-No") String entity,
            @RequestHeader("X-Store-No") String store,
            @RequestHeader("X-Connection-No") String connection,
            @RequestHeader("X-Actor-No") String actor,
            @RequestBody byte[] body) {
        try {
            return InternalServiceResponses.invoke(
                    token,
                    provided,
                    () ->
                            new Handle(
                                    vault.capture(
                                            new ChannelCertificateHandleStore.Scope(
                                                    entity, store, connection),
                                            actor,
                                            body)));
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }
}
