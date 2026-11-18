package ai.neargo.shop.merchantbridge;

import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/** Ordinary promotion uses brand operations and each actual legal entity's separate financial authority. */
@Service
public class BrandPromotionAuthority {
    private static final Set<String> BRAND_PERMISSIONS = Set.of("promotion.activity.read",
            "promotion.activity.manage", "promotion.activity.publish", "promotion.coupon.read",
            "promotion.coupon.manage", "promotion.coupon.issue", "promotion.cost.rule.manage");
    private final ManagementRepository db;
    private final ManagementScopeService scopes;
    private final BrandPromotionScopePort organization;

    public BrandPromotionAuthority(ManagementRepository db, ManagementScopeService scopes,
            BrandPromotionScopePort organization) {
        this.db = db; this.scopes = scopes; this.organization = organization;
    }

    public ActivityActor context(Actor actor, String management, String brand) {
        scopes.resolve(actor, management);
        scopes.require(actor, management, "BRAND", brand, "brand.read");
        return scope(actor, management, brand);
    }

    public boolean canParticipate(Actor actor, String management, String store, String entity) {
        return scopes.ownerAccounts(actor).stream().anyMatch(a -> entity.equals(str(a, "entity_no")))
                || scopes.can(actor, management, "STORE", store, "promotion.participate");
    }

    public ActivityActor resolve(Actor actor, String management, String brand, String permission) {
        ContractChecks.require(BRAND_PERMISSIONS.contains(permission));
        scopes.resolve(actor, management);
        scopes.require(actor, management, "BRAND", brand, permission);
        return scope(actor, management, brand);
    }

    /** Financial readers do not acquire brand marketing or other entities' financial permissions. */
    public ActivityActor financial(Actor actor, String management, String brand, String entity,
            String permission) {
        scopes.resolve(actor, management);
        var context = scope(actor, management, brand);
        requireEntity(actor, management, entity, permission);
        return context;
    }

    private ActivityActor scope(Actor actor, String management, String brand) {
        var row = db.one("SELECT legal_entity_no FROM mch_brand WHERE tenant_no=? AND management_no=? "
                + "AND brand_no=? AND status='ACTIVE' AND deleted=0", actor.tenantNo(), management, brand);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        String issuer = str(row, "legal_entity_no");
        if (issuer == null) throw BizException.of(ErrorCode.CONFLICT);
        var facts = organization.find(actor.tenantNo(), management, brand, issuer);
        if (facts == null) throw BizException.of(ErrorCode.CONFLICT);
        Set<String> stores = new TreeSet<>();
        facts.stores().forEach(s -> stores.add(s.storeNo()));
        return new ActivityActor(actor.tenantNo(), issuer, actor.actorKey(), stores, management, brand);
    }

    public boolean can(Actor actor, String management, String brand, String permission) {
        return BRAND_PERMISSIONS.contains(permission)
                && scopes.can(actor, management, "BRAND", brand, permission);
    }

    public boolean canEntity(Actor actor, String management, String entity, String permission) {
        if (!Set.of("promotion.sponsor.authorize", "promotion.cost.read", "promotion.cost.authorize",
                "promotion.cost.confirm").contains(permission)) return false;
        return scopes.ownerAccounts(actor).stream().anyMatch(a -> entity.equals(str(a, "entity_no")))
                || scopes.can(actor, management, "ENTITY", entity, permission);
    }

    public void requireEntity(Actor actor, String management, String entity, String permission) {
        if (!canEntity(actor, management, entity, permission)) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    /** Called immediately before publication; membership alone never authorizes expense sharing. */
    public void authorizePublication(Actor actor, String management, String brand, List<String> storeNos,
            String ruleVersion) {
        ContractChecks.id(ruleVersion);
        var context = scope(actor, management, brand);
        if (storeNos == null || storeNos.isEmpty() || storeNos.size() != Set.copyOf(storeNos).size()
                || !context.allowedStores().containsAll(storeNos)) throw BizException.of(ErrorCode.CONFLICT);
        requireEntity(actor, management, context.entityNo(), "promotion.sponsor.authorize");
        requireEntity(actor, management, context.entityNo(), "promotion.cost.authorize");
        var facts = organization.find(actor.tenantNo(), management, brand, context.entityNo());
        for (var store : facts.stores()) if (storeNos.contains(store.storeNo())) {
            if (!"CN".equals(store.market())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            boolean owns = scopes.ownerAccounts(actor).stream()
                    .anyMatch(a -> store.entityNo().equals(str(a, "entity_no")));
            if (!owns && !scopes.can(actor, management, "STORE", store.storeNo(), "promotion.participate")) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            requireEntity(actor, management, store.entityNo(), "promotion.cost.authorize");
        }
    }
}
