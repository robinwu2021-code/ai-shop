package ai.neargo.shop.fulfillment.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.fulfillment.dto.CarrierConfigVO;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 承运方可选列表。<b>只读</b>。
 *
 * <p>调拨发货要记「谁在运」，而承运方档案是**全平台共用的事实**，
 * 归履约域维护 —— 进销存再造一张只会让两处名字对不上
 * （见 {@code docs/technical/design/进销存-供应商与发货要素.md} §三②）。
 *
 * <p><b>为什么不复用 {@code /ops/fulfillment/carriers}</b>：那一口判的是运营权限
 * （{@code fulfillment:logistics:read}），商家没有；而且它下发账号掩码、
 * API key 配没配、SLA 小时数 —— 那些是**运营配置**，商家看了既没用也不该看。
 * 这一口只给两列：编号与名字。
 *
 * <p><b>只列启用的</b>。停用的承运方出现在发货选择器里，商家选了之后
 * 那张单指向一个已经不合作的公司，而他不会知道。
 */
@Profile("api")
@RestController
public class BizCarrierController {

    private final LogisticsService logistics;

    public BizCarrierController(LogisticsService logistics) {
        this.logistics = logistics;
    }

    /**
     * 权限用 {@link BizPerms#STOCK}：这一口存在的唯一理由是调拨发货时选承运方，
     * 而调拨本身就判这个码。单为它造一个新码，只会多出一种「能调拨但选不到承运方」的角色。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/fulfillment/carriers")
    public List<CarrierVO> carriers() {
        return logistics.carriers().stream()
                .filter(CarrierConfigVO::enabled)
                .map(c -> new CarrierVO(c.carrier(), c.name()))
                .toList();
    }

    /**
     * @param carrier 承运方编号，如 {@code SF}。<b>进销存那边存的就是它</b>
     *                （{@code inv_transfer_order.carrier_no}），跨库不能外键
     * @param name    名字。端上选中后要**一起回传**给发货接口 ——
     *                进销存读不了主库，那个名字快照只能由端上带过去
     */
    public record CarrierVO(String carrier, String name) {
    }
}
