package ai.neargo.shop.merchant.api.ops;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.paysync.StoreSyncPublisher;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营：代改门店名称与门店链接名（slug，即支付侧立牌门店码 /h5/s/{slug}），迁移与收款验收用
 * （TDD-商户迁移同步与渠道关联 §10）。复用商户端同一服务与唯一校验，改完推一次门店同步。
 */
@Profile("ops")
@RestController
@Validated
public class OpsStoreBasicsController {

    public record BasicsReq(@Size(max = 64) String name, @Size(max = 32) String slug,
                            @NotBlank @Size(max = 255) String reason) {
    }

    private final MchStoreMapper stores;
    private final StoreAdminService admin;
    private final AuditLogPort audit;
    private final ObjectProvider<StoreSyncPublisher> publisher;

    public OpsStoreBasicsController(MchStoreMapper stores, StoreAdminService admin, AuditLogPort audit,
                                   ObjectProvider<StoreSyncPublisher> publisher) {
        this.stores = stores;
        this.admin = admin;
        this.audit = audit;
        this.publisher = publisher;
    }

    @PutMapping("/ops/stores/{storeNo}/basics")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    @Transactional
    public StoreVO update(@PathVariable String storeNo, @Valid @RequestBody BasicsReq req) {
        MchStore s = DataScopeContext.executeWithoutScope(() -> stores.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getStoreNo, storeNo).last("limit 1")));
        if (s == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if ((req.name() == null || req.name().isBlank()) && req.slug() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        String before = s.getName() + "|" + s.getSlug();
        StoreVO vo = admin.rename(s.getEntityNo(), storeNo, req.name() == null ? null : req.name().trim(), null);
        if (req.slug() != null) vo = admin.setSlug(s.getEntityNo(), storeNo, req.slug());
        audit.record("STORE_BASICS_UPDATE", storeNo, before + " → " + req.name() + "|" + req.slug() + "｜" + req.reason());
        publisher.ifAvailable(p -> p.publishAll(List.of(storeNo)));
        return vo;
    }
}
