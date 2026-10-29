package ai.neargo.shop.inventory.route;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.InventoryConsole;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.embed.InventoryErrors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 商家后台的切流开关（ADR-046 M8 T8.3，读写全切）：当前商家的账由谁写，这次请求就交给谁。
 *
 * <ul>
 *   <li>内嵌写 / 还没开过账 → {@link #remote()} 为 false，控制器照旧调领域服务（与切流前一字不差）；</li>
 *   <li>已翻到服务 → 控制器经 {@link #via} 把请求转给服务的 {@code /console} 接口，返回结构逐个相同；</li>
 *   <li>翻转对账中，或已翻却没配客户端 → 409，不悄悄回落内嵌（内嵌这时也不认这本账）。</li>
 * </ul>
 * 判权、数据域照旧在商城控制器上做完（{@code @PreAuthorize}），服务只按凭证 + 商家号限定这本账。
 */
@Component
@ConditionalOnInventory
public class InventoryConsoleRoute {

    private final InventoryAclService acl;
    private final ObjectProvider<InventoryReservationClient> client;

    public InventoryConsoleRoute(InventoryAclService acl, ObjectProvider<InventoryReservationClient> client) {
        this.acl = acl;
        this.client = client;
    }

    /** 当前商家是否已翻到库存服务（每次现查：翻转是在线做的） */
    public boolean remote() {
        String authority = acl.writeAuthorityOf(BizContext.requireMerchantNo());
        if (authority == null || InvEnums.WriteAuthority.EMBEDDED.equals(authority)) {
            return false;
        }
        if (InvEnums.WriteAuthority.SVC.equals(authority) && client.getIfAvailable() != null) {
            return true;
        }
        throw BizException.of(ErrorCode.CONFLICT);
    }

    /** 当前门店号（服务按它解析实际扣库存的库位，与内嵌同一份逻辑） */
    public String store() {
        return BizContext.current().currentStoreNo();
    }

    public <T> T via(Function<InventoryConsole, T> call) {
        try {
            return call.apply(console());
        } catch (InventoryException e) {
            throw InventoryErrors.toShop(e);
        }
    }

    public void run(Consumer<InventoryConsole> call) {
        via(c -> {
            call.accept(c);
            return null;
        });
    }

    private InventoryConsole console() {
        String user = SecurityUtils.currentUserNo();
        return client.getObject().console(BizContext.requireMerchantNo(), user == null ? "SYSTEM:biz" : "STAFF:" + user);
    }
}
