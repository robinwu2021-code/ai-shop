package ai.neargo.shop.portal.biz.feature;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.CommerceModel;
import ai.neargo.commerce.api.common.PlanTier;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Entitlements;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Interval;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.PlanCard;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.PlanCatalog;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Right;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Source;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.StoreContext;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Subscription;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix.FeatureState;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix.PayActivation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** TDD-B端门店功能矩阵与方案准入 AC1–AC7。 */
class StoreFeatureMatrixTest {

    private static final String BIZ = "END_BIZ_APP", FNB = "END_FNB", CHAIN = "END_CHAIN";

    /** 餐饮：FREE 只有基础端；PRO 加餐饮作业；CHAIN 再加连锁。零售、服务业的码都没发布。 */
    private static PlanCatalog fnbCatalog() {
        return new PlanCatalog("FNB", 12L, "PUBLISHED", List.of(
                plan(PlanTier.FREE, List.of(BIZ)),
                plan(PlanTier.PRO, List.of(BIZ, FNB)),
                plan(PlanTier.CHAIN, List.of(BIZ, FNB, CHAIN))));
    }

    private static PlanCard plan(PlanTier tier, List<String> codes) {
        return new PlanCard("P-" + tier, "PV-" + tier, tier, tier.name(), "", codes, "ON_SALE", List.of(),
                "INCLUDED", tier != PlanTier.FREE);
    }

    private static Right right(String code, String status, Long endsAt) {
        return new Right(code, code.substring(4), status,
                "ACTIVE".equals(status) ? new Interval(1L, endsAt) : null,
                List.of(), List.of(), null, null, "ACTIVE".equals(status) ? "CHECK_END_ACCESS" : "VIEW_SUBSCRIPTION");
    }

    /** 新店只有 FREE：基础端 ACTIVE，餐饮与连锁未购。 */
    private static StoreContext freeStore(boolean canProcure, Right... extra) {
        var items = new ArrayList<>(List.of(
                right(BIZ, "ACTIVE", null), right(FNB, "NOT_PURCHASED", null), right(CHAIN, "NOT_PURCHASED", null)));
        for (Right r : extra) {
            items.removeIf(i -> i.entitlementCode().equals(r.entitlementCode()));
            items.add(r);
        }
        var free = new Source("SRC-F", "FREE", "P-FREE", "PV-FREE", "FREE", "FREE", false, 1L, null, null, List.of(BIZ));
        return new StoreContext("S1", "E1", CommerceModel.STORE_V1, "FNB", "ACTIVE", "ACTIVE", canProcure,
                List.of(canProcure ? "OWNER" : "MANAGER"), canProcure, 100L, 7L, null,
                new Subscription("S1", CommerceModel.STORE_V1, 7L, free, null, List.of(free), 100L, null, true, null),
                new Entitlements("S1", CommerceModel.STORE_V1, 7L, List.copyOf(items), 100L, null));
    }

    private static Map<String, FeatureState> byCode(List<FeatureState> features) {
        return features.stream().collect(Collectors.toMap(FeatureState::featureCode, Function.identity()));
    }

    private static final Set<String> OWNER = Set.of("*");

    @Test
    @DisplayName("AC1 矩阵：每个功能在三档是否包含、最低档、6 个预置角色的 NONE/USE/MANAGE；未发布的权益码不返回")
    void matrixForBusinessType() {
        var m = StoreFeatureMatrix.forBusinessType(fnbCatalog());

        assertEquals("FNB", m.businessTypeCode());
        assertEquals(List.of("FREE", "PRO", "CHAIN"), m.tiers().stream().map(t -> t.tier().name()).toList());
        assertEquals(List.of("OWNER", "MANAGER", "CLERK", "PICKER", "COURIER", "CS"), m.roles());

        var f = m.features().stream().collect(Collectors.toMap(StoreFeatureMatrix.MatrixFeature::featureCode,
                Function.identity()));
        assertFalse(f.containsKey("GOODS"), "零售权益码没发布到餐饮方案");
        assertFalse(f.containsKey("SVC_BOOKING"));
        assertFalse(f.containsKey("SOFTPOS"), "END_APP_POS 不在任何一档");

        var collect = f.get("COLLECT");
        assertEquals(PlanTier.FREE, collect.minTier());
        assertEquals(Map.of("FREE", true, "PRO", true, "CHAIN", true), collect.tiers());
        assertTrue(collect.requiresPayActivation());
        assertEquals("MANAGE", collect.roles().get("OWNER"));
        assertEquals("USE", collect.roles().get("CLERK"));
        assertEquals("NONE", collect.roles().get("PICKER"));

        var ordering = f.get("FNB_ORDERING");
        assertEquals(PlanTier.PRO, ordering.minTier());
        assertEquals(Map.of("FREE", false, "PRO", true, "CHAIN", true), ordering.tiers());
        assertEquals("MANAGE", ordering.roles().get("MANAGER"));
        assertEquals("USE", ordering.roles().get("COURIER"));

        assertEquals(PlanTier.CHAIN, f.get("HR").minTier());
        assertEquals("NONE", f.get("HR").roles().get("MANAGER"));
    }

    @Test
    @DisplayName("AC2 FREE 店、收款已开通、店主：基础功能可用，行业功能 NEED_PLAN + requiredTier + VIEW_SUBSCRIPTION")
    void freeStoreOwner() {
        var out = StoreFeatureMatrix.forStore(freeStore(true), fnbCatalog(), OWNER, PayActivation.ACTIVE);
        assertEquals("FREE", out.currentTier());
        assertEquals(PayActivation.ACTIVE, out.payActivation());
        var f = byCode(out.features());

        assertEquals("AVAILABLE", f.get("COLLECT").status());
        assertEquals("ENTER", f.get("COLLECT").nextAction());
        assertTrue(f.get("COLLECT").canUse());
        assertTrue(f.get("COLLECT").canManage());

        var ordering = f.get("FNB_ORDERING");
        assertEquals("NEED_PLAN", ordering.status());
        assertEquals("VIEW_SUBSCRIPTION", ordering.nextAction());
        assertEquals(PlanTier.PRO, ordering.requiredTier());
        assertFalse(ordering.canUse());
        assertEquals(PlanTier.CHAIN, f.get("CHAIN_OPS").requiredTier());
        assertFalse(f.containsKey("GOODS"));
    }

    @Test
    @DisplayName("AC3 没有采购权的人：行业功能 NEED_PLAN + CONTACT_OWNER")
    void freeStoreWithoutProcurement() {
        var perms = BizPerms.of(Set.of(BizPerms.MANAGER));
        var f = byCode(StoreFeatureMatrix.forStore(freeStore(false), fnbCatalog(), perms, PayActivation.ACTIVE)
                .features());
        assertEquals("NEED_PLAN", f.get("FNB_ORDERING").status());
        assertEquals("CONTACT_OWNER", f.get("FNB_ORDERING").nextAction());
    }

    @Test
    @DisplayName("AC4 收款未开通：依赖收款的功能 NEED_PAY_ACTIVATION；有 finance 去开通，否则找店主；读失败不当成未开通")
    void payActivation() {
        var owner = byCode(StoreFeatureMatrix.forStore(freeStore(true), fnbCatalog(), OWNER, PayActivation.NOT_ACTIVE)
                .features());
        assertEquals("NEED_PAY_ACTIVATION", owner.get("COLLECT").status());
        assertEquals("APPLY_PAYMENT", owner.get("COLLECT").nextAction());
        assertEquals("AVAILABLE", owner.get("PAY_ACTIVATION").status(), "开通入口本身不依赖收款");
        assertEquals("AVAILABLE", owner.get("STORE_PROFILE").status());

        var clerk = byCode(StoreFeatureMatrix.forStore(freeStore(false), fnbCatalog(),
                BizPerms.of(Set.of(BizPerms.CLERK)), PayActivation.NOT_ACTIVE).features());
        assertEquals("NEED_PAY_ACTIVATION", clerk.get("COLLECT").status());
        assertEquals("CONTACT_OWNER", clerk.get("COLLECT").nextAction());

        var unknown = byCode(StoreFeatureMatrix.forStore(freeStore(true), fnbCatalog(), OWNER, PayActivation.UNKNOWN)
                .features());
        assertEquals("CHECK_UNAVAILABLE", unknown.get("COLLECT").status());
        assertEquals("RETRY_CHECK", unknown.get("COLLECT").nextAction());
    }

    @Test
    @DisplayName("AC5 方案已含但角色没有使用权限：NO_PERMISSION；canManage 按管理码单独判；自定义权限集合照用")
    void rolePermissions() {
        var pro = freeStore(false, right(FNB, "ACTIVE", 9_000L));
        var picker = byCode(StoreFeatureMatrix.forStore(pro, fnbCatalog(), BizPerms.of(Set.of(BizPerms.PICKER)),
                PayActivation.ACTIVE).features());
        assertEquals("NO_PERMISSION", picker.get("FNB_ORDERING").status());
        assertEquals("CONTACT_OWNER", picker.get("FNB_ORDERING").nextAction());
        assertEquals("AVAILABLE", picker.get("FNB_KITCHEN").status());
        assertFalse(picker.get("FNB_KITCHEN").canManage());

        var clerk = byCode(StoreFeatureMatrix.forStore(pro, fnbCatalog(), BizPerms.of(Set.of(BizPerms.CLERK)),
                PayActivation.ACTIVE).features());
        assertTrue(clerk.get("FNB_ORDERING").canUse());
        assertFalse(clerk.get("FNB_ORDERING").canManage());
        assertEquals(9_000L, clerk.get("FNB_ORDERING").effectiveUntil());

        var custom = byCode(StoreFeatureMatrix.forStore(pro, fnbCatalog(), Set.of(BizPerms.PAY_QR),
                PayActivation.ACTIVE).features());
        assertEquals("AVAILABLE", custom.get("COLLECT_QR").status());
        assertEquals("NO_PERMISSION", custom.get("COLLECT").status());
    }

    @Test
    @DisplayName("AC6 过期 / 未开始 / 撤销 分别给状态与端准入一致的下一步")
    void entitlementLifecycle() {
        var expired = byCode(StoreFeatureMatrix.forStore(freeStore(true, right(FNB, "EXPIRED", null)), fnbCatalog(),
                OWNER, PayActivation.ACTIVE).features());
        assertEquals("PLAN_EXPIRED", expired.get("FNB_ORDERING").status());
        assertEquals("VIEW_SUBSCRIPTION", expired.get("FNB_ORDERING").nextAction());

        var notStarted = byCode(StoreFeatureMatrix.forStore(freeStore(true, right(FNB, "NOT_STARTED", null)),
                fnbCatalog(), OWNER, PayActivation.ACTIVE).features());
        assertEquals("NOT_STARTED", notStarted.get("FNB_ORDERING").status());
        assertEquals("WAIT_FOR_START", notStarted.get("FNB_ORDERING").nextAction());

        var revoked = byCode(StoreFeatureMatrix.forStore(freeStore(true, right(FNB, "REVOKED", null)), fnbCatalog(),
                OWNER, PayActivation.ACTIVE).features());
        assertEquals("REVOKED", revoked.get("FNB_ORDERING").status());
        assertEquals("CONTACT_ADMIN", revoked.get("FNB_ORDERING").nextAction());
    }

    @Test
    @DisplayName("AC7 旧模型门店：不做方案拦截，只按角色与收款判，currentTier 为空")
    void legacyStore() {
        var legacy = new StoreContext("S1", "E1", CommerceModel.LEGACY, "FNB", "ACTIVE", "ACTIVE", true,
                List.of("OWNER"), true, 100L, null, null,
                new Subscription("S1", CommerceModel.LEGACY, null, null, null, List.of(), 100L, null, false,
                        "VIEW_LEGACY_PROFILE"),
                new Entitlements("S1", CommerceModel.LEGACY, null, List.of(), 100L, null));
        var out = StoreFeatureMatrix.forStore(legacy, fnbCatalog(), OWNER, PayActivation.ACTIVE);
        assertNull(out.currentTier());
        var f = byCode(out.features());
        assertEquals("AVAILABLE", f.get("FNB_ORDERING").status());
        assertNull(f.get("FNB_ORDERING").requiredTier());
        assertFalse(f.containsKey("GOODS"), "仍按经营类型的方案过滤行业");
    }

    @Test
    @DisplayName("注册表：featureCode 唯一，权益码都是业务端码")
    void registryIsConsistent() {
        var codes = Arrays.stream(StoreFeature.values()).map(StoreFeature::code).toList();
        assertEquals(codes.size(), Set.copyOf(codes).size());
        assertTrue(Arrays.stream(StoreFeature.values()).allMatch(f -> f.entitlementCode().startsWith("END_")));
    }
}
