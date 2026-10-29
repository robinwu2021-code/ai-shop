package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Set;

/**
 * Original merchant policy creates native decisions using physical selections, never a caller
 * refund amount.
 */
@RestController
@Profile("api")
@ConditionalOnProperty(name = "shop.trade.channel.enabled", havingValue = "true")
@PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
public class BizChannelAfterSaleDecisionController {
    private final IndustryChannelAfterSalePort owner;
    private final ObjectMapper json;

    public BizChannelAfterSaleDecisionController(
            IndustryChannelAfterSalePort owner, ObjectMapper json) {
        this.owner = owner;
        this.json = json;
    }

    @PostMapping("/biz/stores/{storeNo}/channel-after-sale-decisions")
    public IndustryChannelAfterSalePort.Facts authorize(
            @PathVariable String storeNo,
            @RequestBody byte[] body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var scope = scope(storeNo, request);
        var command = read(body, IndustryChannelAfterSalePort.Authorize.class);
        if (command.original() == null || !scope.equals(command.original().scope()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return owner.authorize(command, SecurityUtils.currentUserNo(), key);
    }

    @PostMapping("/biz/stores/{storeNo}/channel-after-sale-decisions/release-unissued")
    public IndustryChannelAfterSalePort.Facts release(
            @PathVariable String storeNo, @RequestBody byte[] body, HttpServletRequest request) {
        var scope = scope(storeNo, request);
        var query = read(body, IndustryChannelAfterSalePort.Query.class);
        if (!scope.equals(query.scope())) throw BizException.of(ErrorCode.FORBIDDEN);
        return owner.releaseUnissued(query, SecurityUtils.currentUserNo());
    }

    private static Scope scope(String store, HttpServletRequest request) {
        if (!request.getParameterMap().keySet().equals(Set.of()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var context = BizContext.current();
        String header = request.getHeader(BizContextFilter.STORE_HEADER);
        if (!Objects.equals(store, context.currentStoreNo())
                || !context.storeNos().contains(store)
                || header != null && !store.equals(header))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return new Scope("MAIN", context.requireMerchantNo(), store);
    }

    private <T> T read(byte[] body, Class<T> type) {
        if (body == null || body.length == 0 || body.length > 262144)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        try {
            return json.readerFor(type)
                    .with(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
        } catch (RuntimeException bad) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
