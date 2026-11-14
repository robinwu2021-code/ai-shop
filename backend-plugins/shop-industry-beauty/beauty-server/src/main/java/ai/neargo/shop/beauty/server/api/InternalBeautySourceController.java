package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl;
import ai.neargo.shop.beauty.spi.BeautySourceInternalApi;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Reverse callback resolves only the actual local appointment source, never another registered industry. */
@RestController
@ConditionalOnBeautyEnabled
public class InternalBeautySourceController {
    private final ObjectProvider<AppointmentSourceAuthorityPortImpl> source;
    private final String token;
    public InternalBeautySourceController(ObjectProvider<AppointmentSourceAuthorityPortImpl> source,
            @Value("${shop.services.internal-token:}") String token) { this.source = source; this.token = token; }
    @PostMapping(BeautySourceInternalApi.BASE + "freeze")
    public ResponseEntity<?> freeze(@RequestBody BeautySourceInternalApi.Delegation request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(request, given, true);
    }
    @PostMapping(BeautySourceInternalApi.BASE + "query")
    public ResponseEntity<?> query(@RequestBody BeautySourceInternalApi.Delegation request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(request, given, false);
    }
    private ResponseEntity<?> invoke(BeautySourceInternalApi.Delegation body, String given, boolean freeze) {
        try { return InternalServiceResponses.invoke(token, given, () -> {
            var i = body.identity(); var r = body.request();
            if (i == null || r == null || !"BEAUTY_APPOINTMENT".equals(r.binding().sourceType())
                    || !i.actorNo().equals(r.operatorNo()) || !i.realm().equals(r.realm())
                    || !i.subjectKind().equals(r.subjectKind()) || !i.tenantNo().equals(r.binding().tenantNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return DelegatedIdentityContext.call(i, () -> freeze ? source.getObject().freeze(r)
                    : source.getObject().query(r));
        }); } catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    /** Internal decode failures use real status codes even when the host has public HTTP200 error advice. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
