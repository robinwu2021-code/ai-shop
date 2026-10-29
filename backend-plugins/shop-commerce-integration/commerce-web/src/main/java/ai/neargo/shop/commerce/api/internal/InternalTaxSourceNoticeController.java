package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.service.TaxSourceNoticeService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.TaxSourceNoticeInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

@RestController
public class InternalTaxSourceNoticeController {
    private final TaxSourceNoticeService service;
    private final ObjectMapper json;
    private final String token;

    public InternalTaxSourceNoticeController(
            TaxSourceNoticeService service,
            ObjectMapper json,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.json = json;
        this.token = token;
    }

    @PostMapping(TaxSourceNoticeInternalApi.RECEIVE_PATH)
    public ResponseEntity<?> receive(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody byte[] body) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    TaxSourceNoticeContracts.Notice notice;
                    try {
                        notice =
                                CatalogSourceTransportPolicy.read(
                                        json, body, TaxSourceNoticeContracts.Notice.class);
                    } catch (RuntimeException invalid) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return service.receive(notice);
                });
    }
}
