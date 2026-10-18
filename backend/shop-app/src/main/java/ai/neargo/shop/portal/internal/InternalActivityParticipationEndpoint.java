package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationInternal;
import ai.neargo.shop.spi.promotion.ActivityParticipationInternal.ReserveRequest;
import ai.neargo.shop.spi.promotion.ActivityParticipationInternal.ConfirmRequest;
import ai.neargo.shop.spi.promotion.ActivityParticipationInternal.ReleaseRequest;
import ai.neargo.shop.spi.promotion.ActivityParticipationInternal.QueryRequest;
import ai.neargo.shop.svc.InternalHttp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Fail-closed internal service transport; source facts are still verified by the source owner. */
@RestController
@RequestMapping(ActivityParticipationInternal.BASE)
public class InternalActivityParticipationEndpoint {
    private final ActivityParticipationPort participation;
    private final String token;
    public InternalActivityParticipationEndpoint(ActivityParticipationPort participation,
            @Value("${shop.services.internal-token:}") String token) {
        this.participation=participation; this.token=token;
    }
    @PostMapping("/reserve") public ResponseEntity<?> reserve(
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given,@RequestBody ReserveRequest req) {
        if(!authorized(given)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(participation.reserve(req.context(),req.command()));
    }
    @PostMapping("/confirm") public ResponseEntity<?> confirm(
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given,@RequestBody ConfirmRequest req) {
        if(!authorized(given)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(participation.confirm(req.context(),req.command()));
    }
    @PostMapping("/release") public ResponseEntity<?> release(
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given,@RequestBody ReleaseRequest req) {
        if(!authorized(given)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(participation.release(req.context(),req.command()));
    }
    @PostMapping("/query") public ResponseEntity<?> query(
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given,@RequestBody QueryRequest req) {
        if(!authorized(given)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(participation.query(req.context(),req.source()));
    }
    private boolean authorized(String given) {
        return token!=null && !token.isBlank() && given!=null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),given.getBytes(StandardCharsets.UTF_8));
    }
}
