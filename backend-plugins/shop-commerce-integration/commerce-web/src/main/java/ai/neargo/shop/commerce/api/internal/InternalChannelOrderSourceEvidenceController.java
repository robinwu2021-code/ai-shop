package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.commerce.policy.ChannelOrderSourceEvidencePolicy;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidenceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort.Query;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The original service-token boundary is shared by embedded and independent deployment. */
@RestController
public class InternalChannelOrderSourceEvidenceController {
    private final ChannelOrderSourceEvidencePort source;
    private final String token;

    public InternalChannelOrderSourceEvidenceController(
            ChannelOrderSourceEvidencePort source,
            @Value("${shop.services.internal-token:}") String token) {
        this.source = source;
        this.token = token;
    }

    @PostMapping(ChannelOrderSourceEvidenceInternalApi.CATALOG_PATH)
    public ResponseEntity<?> publishedCatalog(
            @RequestBody Query request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    ChannelOrderSourceEvidencePolicy.query(request);
                    return source.publishedCatalog(request);
                });
    }

    @PostMapping(ChannelOrderSourceEvidenceInternalApi.READ_PATH)
    public ResponseEntity<?> read(
            @RequestBody Query request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    ChannelOrderSourceEvidencePolicy.query(request);
                    return source.read(request);
                });
    }
}
