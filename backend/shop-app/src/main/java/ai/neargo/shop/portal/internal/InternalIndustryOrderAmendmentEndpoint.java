package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalIndustryOrderAmendmentEndpoint {
    private final IndustryOrderAmendmentPort owner;
    private final String token;

    public InternalIndustryOrderAmendmentEndpoint(
            IndustryOrderAmendmentPort owner,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner;
        this.token = token;
    }

    @PostMapping(IndustryOrderAmendmentInternalApi.ROOT + "/amend")
    public ResponseEntity<?> amend(
            @RequestBody IndustryOrderAmendmentInternalApi.Amend c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> owner.amend(c.command(), c.key()));
    }

    @PostMapping(IndustryOrderAmendmentInternalApi.ROOT + "/fee")
    public ResponseEntity<?> fee(
            @RequestBody IndustryOrderAmendmentInternalApi.Fee c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> owner.adjustFee(c.command(), c.key()));
    }

    @PostMapping(IndustryOrderAmendmentInternalApi.ROOT + "/query")
    public ResponseEntity<?> query(
            @RequestBody IndustryOrderAmendmentInternalApi.Query c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> owner.query(c.reference(), c.actorNo(), c.key()));
    }
}
