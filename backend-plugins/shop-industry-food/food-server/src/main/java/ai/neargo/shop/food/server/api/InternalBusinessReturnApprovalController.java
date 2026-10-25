package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApprovalVO;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi.Approve;
import ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi.Query;
import ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi.Replacement;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourceInternalApi;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalBusinessReturnApprovalController {
    private final BusinessReturnApprovalService service;
    private final IndustryAmendmentSourcePort source;

    public InternalBusinessReturnApprovalController(
            BusinessReturnApprovalService service, IndustryAmendmentSourcePort source) {
        this.service = service;
        this.source = source;
    }

    @PostMapping("/internal/food/return-approval/approve")
    public ApprovalVO approve(@RequestBody Approve c) {
        return service.approve(
                c.entity(), c.store(), c.actor(), c.returnNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/return-approval/get")
    public ApprovalVO get(@RequestBody Query c) {
        return service.get(c.entity(), c.store(), c.actor(), c.returnNo());
    }

    @PostMapping("/internal/food/return-approval/replacement")
    public ApprovalVO replacement(@RequestBody Replacement c) {
        return service.linkReplacement(
                c.entity(), c.store(), c.actor(), c.returnNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/amendment-source/query")
    public IndustryAmendmentSourcePort.Evidence source(
            @RequestBody IndustryAmendmentSourceInternalApi.Query c) {
        return source.get(c.reference(), c.sourceReturnNo());
    }
}
