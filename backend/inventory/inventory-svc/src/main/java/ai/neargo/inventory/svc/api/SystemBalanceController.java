package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.BalanceQueryService;
import ai.neargo.inventory.domain.service.BalanceQueryService.OwnerBalances;
import ai.neargo.inventory.domain.service.BalanceQueryService.OwnerKey;
import ai.neargo.inventory.guard.InventoryRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 系统级 · 多账本批量余额（TDD §2.19、AC-33）：只认系统凭证（过滤器对 {@code /system/} 已拦账本凭证）。
 * 宿主在业务侧汇总多本账（连锁看各门店），服务不保存它们之间的组织关系。
 */
@RestController
public class SystemBalanceController {

    private final BalanceQueryService balances;

    public SystemBalanceController(BalanceQueryService balances) {
        this.balances = balances;
    }

    @PostMapping("/api/inventory/v1/system/balances/query")
    public List<OwnerBalances> query(@Validated @RequestBody Query req) {
        return balances.acrossOwners(InventoryRequestContext.principal().systemCode(),
                req.owners().stream().map(o -> new OwnerKey(o.type(), o.ref())).toList(),
                req.itemRefs() == null ? Set.of() : req.itemRefs());
    }

    public record Query(@NotEmpty @Size(max = 50) List<@Valid Owner> owners, @Size(max = 200) Set<String> itemRefs) {
    }

    /** @param type 账本的宿主组织类型；可省，同号多本时该项报 {@code owner_type_required} */
    public record Owner(String type, @NotBlank String ref) {
    }
}
