package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.common.StoreMarkets;
import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本店能选的快递公司（TDD-商户App快递公司）。
 *
 * <p><b>和 {@code ship()} 校验用的是同一份表</b>（{@link ExpressCompanies}），市场取法也是同一处（{@link StoreMarkets}）——
 * 清单里能选的，发货与发货设置一定收；清单外的，一定拒。两份各自维护的话，台湾门店会选得到、发不出去。
 *
 * <p>为什么回对象不回裸数组：App 从 {@code /biz/stores/{storeNo}/context} 拿到的市场是<b>原值</b>（为空不回落），
 * 它不知道部署默认市场是什么，存量主体会被判错；能不能叫快递也不该由 App 按市场猜。两个能力位放在
 * 发货页本来就要调的这个接口里。
 *
 * <p>权限是 {@code biz:ship} 或 {@code biz:store} 任一：发货页要它，发货设置页（读是 {@code biz:store}）也要它，
 * 只给其中一个码，另一个页面的人就读不到。
 */
@Profile("api")
@RestController
public class BizExpressCompanyController {

    private final StoreMarkets markets;
    private final ExpressPickupPort pickup;
    private final LogisticsPort logistics;

    public BizExpressCompanyController(StoreMarkets markets, ExpressPickupPort pickup, LogisticsPort logistics) {
        this.markets = markets;
        this.pickup = pickup;
        this.logistics = logistics;
    }

    /**
     * @param market                 已按 {@code shop.merchant.default-market} 回落的经营市场
     * @param courierPickupAvailable 能不能叫快递：通道开着，且通道能叫的承运商与本市场清单有交集
     *                               （叫快递的承运商全是大陆码，台湾门店恒为 false）
     * @param companies              顺序即发货界面的显示顺序；不认得的市场为空
     */
    public record CompanyList(String market, boolean courierPickupAvailable, List<Company> companies) {
    }

    /** @param traceSupported false = 照样能发货，只是查不到轨迹 */
    public record Company(String code, String name, boolean traceSupported) {
    }

    @PreAuthorize("@perm.canAnyBiz('" + BizPerms.SHIP + "','" + BizPerms.STORE + "')")
    @GetMapping("/biz/express/companies")
    public CompanyList list() {
        String market = markets.of(BizContext.requireStoreNo());
        List<ExpressCompanies.Company> listed = ExpressCompanies.forMarket(market);
        boolean pickupAvailable = pickup.enabled()
                && pickup.carriers().stream().anyMatch(code -> ExpressCompanies.isValidFor(market, code));
        return new CompanyList(market, pickupAvailable, listed.stream()
                .map(c -> new Company(c.code(), c.name(), logistics.traceCovers(c.code())))
                .toList());
    }
}
