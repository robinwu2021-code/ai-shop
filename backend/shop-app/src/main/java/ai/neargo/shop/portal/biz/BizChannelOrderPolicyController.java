package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService.Policy;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService.SaveReq;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/**
 * Merchant operation controls original channel receivable admission; no platform approval is
 * introduced.
 */
@Profile("api")
@RestController
@Validated
@ConditionalOnProperty(prefix = "shop.trade.channel", name = "enabled", havingValue = "true")
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelOrderPolicyController {
    private final IndustryChannelOrderPolicyService policies;

    public BizChannelOrderPolicyController(IndustryChannelOrderPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/biz/stores/{storeNo}/channel-order-policies")
    public PageData<Policy> list(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        query(request, Set.of("page", "size"));
        return policies.list(scope(storeNo), page, size);
    }

    @GetMapping("/biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}")
    public Policy get(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @PathVariable @NotBlank @Size(max = 64) String channelStoreKey,
            HttpServletRequest request) {
        query(request, Set.of());
        return policies.get(scope(storeNo), channelStoreKey);
    }

    @PostMapping("/biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}")
    public Policy save(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @PathVariable @NotBlank @Size(max = 64) String channelStoreKey,
            @Valid @RequestBody SaveReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        query(request, Set.of());
        return policies.save(
                scope(storeNo), SecurityUtils.currentUserNo(), channelStoreKey, body, key);
    }

    private static Scope scope(String store) {
        var context = BizContext.current();
        if (!Objects.equals(store, context.currentStoreNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return new Scope("MAIN", context.requireMerchantNo(), store);
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream()
                        .anyMatch(values -> values.length != 1))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
