package ai.neargo.shop.platform.api.common;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.platform.AppOpsTicketService;
import ai.neargo.shop.platform.dto.AppOpsTicketVOs.TicketReq;
import ai.neargo.shop.platform.dto.AppOpsTicketVOs.TicketVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AppOps installation enrollment ticket for the merchant app (nearboss online upgrades, plan B).
 *
 * <p>Anonymous under {@code /common}, like the version check: an app enrolls its AppOps installation at first start,
 * before anyone signs in. The ticket is single use and bound to the given public key; {@code no-store}.
 */
@Profile("api")
@RestController
public class AppOpsTicketController {

    private final AppOpsTicketService service;

    public AppOpsTicketController(AppOpsTicketService service) {
        this.service = service;
    }

    @PostMapping("/common/appops/enrollment-ticket")
    public ResponseEntity<TicketVO> issue(@RequestBody TicketReq req, HttpServletRequest http) {
        // The public chain sets no RequestMetaContext; ClientMeta trusts X-Real-IP only from our own proxies.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.issue(req.app(), req.publicKey(), ClientMeta.clientIp(http)));
    }
}
