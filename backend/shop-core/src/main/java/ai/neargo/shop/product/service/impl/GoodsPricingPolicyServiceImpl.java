package ai.neargo.shop.product.service.impl;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.Context;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.PolicyVO;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import ai.neargo.shop.spi.product.GoodsPricingPolicyFacts;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Policy;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Snapshot;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Store-owned SKU metadata; CAS and receipt commit together, without changing price or stock.
 *
 * <p>门店交易库里策略是 {@code prd_sku} 的两列（{@code tax_class}、{@code promotion_eligible}），
 * 写在本市场那一行上。商品归属唯一一家门店（{@link #owned} 校验 goods.store_no），
 * 所以原表的 store_no 维度并进 SKU 不丢信息。策略编号用 sku_no，版本用该行的乐观锁 version；
 * 未配时对外版本为 0，第一次保存必须带 0。
 */
@Service
public class GoodsPricingPolicyServiceImpl implements GoodsPricingPolicyService {
    /**
     * 本市场：SKU 以这个市场的那一行为准。按部署配置 {@code shop.merchant.default-market}（正式环境 TW），
     * 不写死；没配时与 {@code StoreMarkets} 一样回落 CN。手工构造（单测）时也是 CN。
     */
    private String homeMarket = "CN";

    @Value("${shop.merchant.default-market:CN}")
    void setHomeMarket(String market) {
        this.homeMarket = market.trim().toUpperCase(Locale.ROOT);
    }

    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final RequestIdempotencyPort idempotency;
    private final TransactionTemplate transactions;
    public GoodsPricingPolicyServiceImpl(GoodsMapper goods, SkuMapper skus,
            RequestIdempotencyPort idempotency, @Qualifier(ModuleDataSources.PRODUCT_TX_MANAGER) PlatformTransactionManager manager) {
        this.goods = goods; this.skus = skus; this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(manager);
    }
    @Override public PolicyVO get(Context context, String goodsNo, String skuNo) {
        if (context == null) throw bad();
        var ref = ref(goodsNo, skuNo);
        var sku = owned(context.scope(), ref);
        if (!configured(sku)) throw BizException.of(ErrorCode.NOT_FOUND);
        return PolicyVO.from(policy(context.scope(), ref, sku));
    }
    @Override public PolicyVO save(Context context, String goodsNo, String skuNo, SaveReq request, String key) {
        if (context == null || request == null || request.promotionEligible() == null || request.taxClass() == null
                || request.taxClass() == TaxClass.UNKNOWN || request.expectedVersion() < 0
                || request.expectedVersion() >= 9007199254740991L || key == null || key.isBlank()
                || key.length() > 128) throw bad();
        var ref = ref(goodsNo, skuNo);
        owned(context.scope(), ref);
        String endpoint = "GOODS_PRICING:" + PricingSnapshotHash.of(List.of(context.scope(), ref));
        String hash = PricingSnapshotHash.of(request);
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(status -> idempotency.execute(key, endpoint, context.actorNo(), hash,
                        PolicyVO.class, () -> change(context, ref, request)));
            } catch (PessimisticLockingFailureException failure) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure;
                owned(context.scope(), ref);
                var receipt = idempotency.findCompleted(key, endpoint, context.actorNo(), hash, PolicyVO.class);
                if (receipt.isPresent()) return receipt.get();
                if (attempt >= 2) throw conflict();
            } catch (DuplicateKeyException failure) { throw conflict(); }
        }
    }
    private PolicyVO change(Context context, SkuRef ref, SaveReq request) {
        var scope = context.scope();
        var sku = owned(scope, ref);
        long rowVersion = sku.getVersion() == null ? 0 : sku.getVersion();
        if (exposedVersion(sku) != request.expectedVersion()) throw conflict();
        var update = new UpdateWrapper<PrdSku>().eq("id", sku.getId()).eq("deleted", 0)
                .eq("version", rowVersion).set("version", rowVersion + 1)
                .set("promotion_eligible", request.promotionEligible()).set("tax_class", request.taxClass().name())
                .set("updated_at", LocalDateTime.now()).set("updated_by", context.actorNo());
        if (DataScopeContext.executeWithoutScope(() -> skus.update(null, update)) != 1) throw conflict();
        return PolicyVO.from(policy(scope, ref, owned(scope, ref)));
    }
    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER, readOnly = true)
    public Snapshot resolve(Scope scope, List<SkuRef> requested) {
        List<SkuRef> refs;
        try { refs = GoodsPricingPolicyFacts.request(scope, requested); }
        catch (RuntimeException invalid) { throw blocked(); }
        var result = new ArrayList<Policy>();
        for (var ref : refs) {
            result.add(policy(scope, ref, owned(scope, ref)));
        }
        return GoodsPricingPolicyFacts.freeze(scope, result);
    }
    @Override
    public Map<String, TaxClass> taxClasses(Scope scope, Collection<String> skuNos) {
        var out = new HashMap<String, TaxClass>();
        if (skuNos.isEmpty()) return out;
        var rows = DataScopeContext.executeWithoutScope(() -> skus.selectList(
                new QueryWrapper<PrdSku>().select("sku_no", "goods_no", "tax_class")
                        .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo())
                        .eq("market", homeMarket).in("sku_no", skuNos).isNotNull("tax_class").eq("deleted", 0)));
        if (rows.isEmpty()) return out;
        // 原表按 store_no 取；策略并进 SKU 后，门店维度由商品归属给出
        var goodsNos = new HashSet<String>();
        rows.forEach(row -> goodsNos.add(row.getGoodsNo()));
        var storeGoods = new HashSet<String>();
        DataScopeContext.executeWithoutScope(() -> goods.selectList(new QueryWrapper<PrdGoods>().select("goods_no")
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .in("goods_no", goodsNos).eq("deleted", 0))).forEach(g -> storeGoods.add(g.getGoodsNo()));
        for (var row : rows) {
            if (storeGoods.contains(row.getGoodsNo())) out.put(row.getSkuNo(), TaxClass.valueOf(row.getTaxClass()));
        }
        return out;
    }
    private PrdSku owned(Scope scope, SkuRef ref) {
        var sku = DataScopeContext.executeWithoutScope(() -> skus.selectOne(new QueryWrapper<PrdSku>()
                .select("id", "tenant_no", "sku_no", "goods_no", "entity_no", "market", "tax_class",
                        "promotion_eligible", "version")
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("goods_no", ref.goodsNo())
                .eq("sku_no", ref.skuNo()).eq("market", homeMarket).eq("deleted", 0)));
        var owner = DataScopeContext.executeWithoutScope(() -> goods.selectOne(new QueryWrapper<PrdGoods>()
                .select("goods_no", "entity_no", "store_no")
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .eq("goods_no", ref.goodsNo()).eq("deleted", 0)));
        if (sku == null || owner == null || !ref.goodsNo().equals(sku.getGoodsNo())
                || !ref.skuNo().equals(sku.getSkuNo()) || !scope.entityNo().equals(sku.getEntityNo())
                || !homeMarket.equals(sku.getMarket()) || !ref.goodsNo().equals(owner.getGoodsNo())
                || !scope.entityNo().equals(owner.getEntityNo()) || !scope.storeNo().equals(owner.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sku;
    }
    private static boolean configured(PrdSku sku) {
        return sku.getTaxClass() != null && sku.getPromotionEligible() != null;
    }
    /** 未配时对外是 0；配过之后是该行的 version（第一次保存即 ≥ 1，不会与「未配」混淆）。 */
    private static long exposedVersion(PrdSku sku) {
        return !configured(sku) || sku.getVersion() == null ? 0 : sku.getVersion();
    }
    private Policy policy(Scope scope, SkuRef ref, PrdSku sku) {
        try {
            if (sku == null || !configured(sku) || !scope.tenantNo().equals(sku.getTenantNo())
                    || !scope.entityNo().equals(sku.getEntityNo())
                    || !ref.goodsNo().equals(sku.getGoodsNo()) || !ref.skuNo().equals(sku.getSkuNo())
                    || sku.getVersion() == null) {
                throw new IllegalArgumentException();
            }
            var value = new Policy(sku.getSkuNo(), sku.getGoodsNo(), sku.getSkuNo(), sku.getVersion(),
                    sku.getPromotionEligible(), TaxClass.valueOf(sku.getTaxClass()));
            GoodsPricingPolicyFacts.freeze(scope, List.of(value)); return value;
        } catch (RuntimeException invalid) { throw blocked(); }
    }
    private SkuRef ref(String goodsNo, String skuNo) {
        try { return new SkuRef(goodsNo, skuNo); }
        catch (RuntimeException invalid) { throw bad(); }
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
