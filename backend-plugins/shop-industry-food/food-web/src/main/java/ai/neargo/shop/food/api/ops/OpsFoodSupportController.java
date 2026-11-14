package ai.neargo.shop.food.api.ops;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.dto.FoodSupportContracts.ReadinessVO;
import ai.neargo.shop.food.service.FoodSupportService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Merchant-bound support reads preserve the operator data domain and original store ownership. */
@RestController
@Profile("ops")
@ConditionalOnFoodWebEnabled
@Validated
public class OpsFoodSupportController {
    private final FoodSupportService service;
    private final StoreDirectoryPort stores;

    public OpsFoodSupportController(FoodSupportService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }

    @GetMapping("/ops/x/food/support/readiness")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_READ + "')")
    public ReadinessVO readiness(
            @RequestParam @NotBlank @Size(max = 32) String entityNo,
            @RequestParam @NotBlank @Size(max = 32) String storeNo) {
        requireVisible(entityNo, storeNo);
        return service.readiness();
    }

    @GetMapping("/ops/x/food/support/cases")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public List<CaseVO> cases(
            @RequestParam @NotBlank @Size(max = 32) String entityNo,
            @RequestParam @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String status) {
        requireVisible(entityNo, storeNo);
        return service.cases(entityNo, storeNo, status);
    }

    @GetMapping("/ops/x/food/support/source")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public Evidence evidence(
            @RequestParam @NotBlank @Size(max = 32) String entityNo,
            @RequestParam @NotBlank @Size(max = 32) String storeNo,
            @RequestParam @NotBlank @Size(max = 16) String kind,
            @RequestParam @NotBlank @Size(max = 64) String sourceNo) {
        requireVisible(entityNo, storeNo);
        return service.evidence(entityNo, storeNo, new Source(kind, sourceNo));
    }

    private void requireVisible(String entityNo, String storeNo) {
        var user =
                SecurityUtils.currentUser().orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        if (user.realm() != Realm.OPERATOR
                || entityNo == null
                || entityNo.isBlank()
                || storeNo == null
                || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        DataScopeSpec scope = user.dataScope();
        if (scope == null
                || (!scope.all()
                        && scope.rules().stream()
                                .noneMatch(
                                        rule ->
                                                ScopeDim.MERCHANT.equals(rule.dim())
                                                        && rule.refs() != null
                                                        && rule.refs().contains(entityNo)))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var store = stores.cards(List.of(storeNo)).get(storeNo);
        if (store == null
                || !entityNo.equals(store.entityNo())
                || !storeNo.equals(store.storeNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}
