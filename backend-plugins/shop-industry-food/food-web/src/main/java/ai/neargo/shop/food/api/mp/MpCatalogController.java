package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public H5 and table-session QR are separate authenticated source paths, never caller-chosen staff/channel identity.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpCatalogController {
    private final CatalogService service;
    public MpCatalogController(CatalogService service) { this.service = service; }
    @GetMapping("/mp/x/food/catalog")
    public CatalogVO read(@RequestParam("storeNo") @NotBlank @Size(max=32) String store,
            @RequestParam("orderSource") @NotBlank String source,
            @RequestParam("fulfillment") @NotBlank String fulfillment,
            @RequestParam(value="sessionNo", required=false) @Size(max=32) String session,
            @RequestHeader(value="X-Food-Session", required=false) @Size(max=128) String access,
            HttpServletRequest request) {
        if (!Set.of("storeNo", "orderSource", "fulfillment", "sessionNo")
                .containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) bad();
        if ("H5".equals(source) && session == null && access == null
                && ("PICKUP".equals(fulfillment) || "DELIVERY".equals(fulfillment))) {
            return service.publicCatalog(store, fulfillment);
        }
        if ("QR".equals(source) && "DINE_IN".equals(fulfillment)
                && session != null && !session.isBlank() && access != null && !access.isBlank()) {
            String buyer = SecurityUtils.currentUser().filter(u -> u.isConsumer()).map(u -> u.userNo()).orElse(null);
            return service.guest(store, session, access, buyer);
        }
        bad(); return null;
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
}
