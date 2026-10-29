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
        return remoteFor(BizContext.requireMerchantNo());
    }

    /** 指定商家（运营端按请求参数里的商家号）是否已翻到库存服务 */
    public boolean remoteFor(String entityNo) {
        return decide(acl.writeAuthorityOf(entityNo));
    }

    /** 指定业主（开放接口按 AppKey 认出的业主）：已翻到服务时返回它的商家号，否则为空 */
    public String remoteEntityOfOwner(String ownerId) {
        InventoryAclService.OwnerRoute r = acl.routeOfOwner(ownerId);
        return r != null && decide(r.writeAuthority()) ? r.entityNo() : null;
    }

    /** 以指定商家、指定操作人转发（运营端、开放接口：不在商家登录上下文里） */
    public <T> T viaFor(String entityNo, String actor, Function<InventoryConsole, T> call) {
        try {
            return call.apply(client.getObject().console(entityNo, actor == null ? "SYSTEM" : actor));
        } catch (InventoryException e) {
            throw InventoryErrors.toShop(e);
        }
    }

    private boolean decide(String authority) {
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

    /**
     * 当前员工的门店范围（门店号，逗号分隔），店主（整个商家）为空。转给服务的单据请求带上它，
     * 服务按与内嵌相同的规则判「单据涉及的库位是不是他动得到的」。
     */
    public String scopeStores() {
        BizContext ctx = BizContext.current();
        if (ctx.owner()) {
            return null;
        }
        return String.join(",", ctx.storeNos() == null ? java.util.Set.of() : ctx.storeNos());
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
        // 操作人原样透传（与内嵌时期写进单据的值相同），审计与单据上看到的是同一个人
        String user = SecurityUtils.currentUserNo();
        return client.getObject().console(BizContext.requireMerchantNo(), user == null ? "SYSTEM" : user);
    }
}
