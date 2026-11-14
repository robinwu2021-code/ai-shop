package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.LocalStockControlService;
import ai.neargo.shop.spi.product.LocalStockControlPort.Preflight;
import ai.neargo.shop.spi.product.LocalStockControlPort.Request;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
@Validated
public class BizLocalStockController {
    private final LocalStockControlService service;
    private final LocalStockAccessPolicy access;
    public BizLocalStockController(LocalStockControlService service, LocalStockAccessPolicy access) {
        this.service = service; this.access = access;
    }
    @PostMapping("/biz/stock-control/preflight")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public Preflight preflight(@Valid @RequestBody Request request,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        return service.preflight(access.context(requestedStore), request);
    }
}
