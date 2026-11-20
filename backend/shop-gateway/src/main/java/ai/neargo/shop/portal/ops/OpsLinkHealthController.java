package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.invbridge.LinkHealthService;
import ai.neargo.shop.invbridge.LinkHealthService.ChannelHealth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 投影链路健康度（M3）。
 *
 * <p>与「库存对差」是<b>两件事</b>：那一页读的是数据（账上有多少、实际有多少），
 * 这一页读的是链路（事件投出去了没有）。09-02 的教训正是它们被混成了一个数 ——
 * 「待搬 1 个」看起来像一条数据要补，实际是整条投递链停了六个小时。
 *
 * <p><b>只有读。</b>重投是投递任务自己的事；给运营一个「手动重投」按钮
 * 会让「投递为什么停了」这个问题被一次点击盖过去。
 */
@Profile("ops")
@RestController
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsLinkHealthController {

    private final LinkHealthService link;

    public OpsLinkHealthController(LinkHealthService link) {
        this.link = link;
    }

    /** 恒返回两行（两条方向各一行）。少一行是查不到，不是那条链路没事 */
    @PreAuthorize("@perm.can('" + Perms.INVENTORY_STOCK_READ + "')")
    @GetMapping("/ops/inventory/link-health")
    public List<ChannelHealth> linkHealth() {
        return link.scan();
    }
}
