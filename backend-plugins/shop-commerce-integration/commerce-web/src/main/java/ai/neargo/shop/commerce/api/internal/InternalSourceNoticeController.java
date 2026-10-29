package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.spi.commerce.SourceNoticeInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACK means the original durable notice committed, never that a menu reached an external platform.
 */
@RestController
public class InternalSourceNoticeController {
    private final SourceNoticeService service;
    private final String token;

    public InternalSourceNoticeController(
            SourceNoticeService service, @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(SourceNoticeInternalApi.RECEIVE_PATH)
    public ResponseEntity<?> receive(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody Notice notice) {
        return InternalServiceResponses.invoke(token, given, () -> service.receive(notice));
    }
}
