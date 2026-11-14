package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.Context;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.PolicyVO;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdSkuPricingPolicy;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuPricingPolicyMapper;
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
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Store-owned SKU metadata; CAS and receipt commit together, without changing price or stock. */
@Service
public class GoodsPricingPolicyServiceImpl implements GoodsPricingPolicyService {
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final SkuPricingPolicyMapper policies;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transactions;
    public GoodsPricingPolicyServiceImpl(GoodsMapper goods, SkuMapper skus, SkuPricingPolicyMapper policies,
            IdempotencyService idempotency, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.goods = goods; this.skus = skus; this.policies = policies; this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(manager);
    }
    @Override public PolicyVO get(Context context, String goodsNo, String skuNo) {
        if (context == null) throw bad();
        var ref = ref(goodsNo, skuNo);
        owned(context.scope(), ref);
        var row = row(context.scope(), ref);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return PolicyVO.from(policy(context.scope(), ref, row));
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
        var scope = context.scope(); owned(scope, ref);
        var original = row(scope, ref);
        long version = original == null ? 0 : original.getVersion();
        if (version != request.expectedVersion()) throw conflict();
        var now = LocalDateTime.now();
        if (original == null) {
            original = new PrdSkuPricingPolicy();
            original.setPolicyNo(BizKey.next(BizKey.SKU_PRICING_POLICY)); original.setTenantNo(scope.tenantNo());
            original.setEntityNo(scope.entityNo()); original.setStoreNo(scope.storeNo());
            original.setGoodsNo(ref.goodsNo()); original.setSkuNo(ref.skuNo());
            original.setPromotionEligible(request.promotionEligible()); original.setTaxClass(request.taxClass().name());
            original.setVersion(1L); original.setDeleted(0); original.setCreatedAt(now); original.setUpdatedAt(now);
            original.setCreatedBy(context.actorNo()); original.setUpdatedBy(context.actorNo());
            var inserted = original;
            if (DataScopeContext.executeWithoutScope(() -> policies.insert(inserted)) != 1) throw conflict();
        } else {
            var update = new UpdateWrapper<PrdSkuPricingPolicy>().eq("tenant_no", scope.tenantNo())
                    .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                    .eq("goods_no", ref.goodsNo()).eq("sku_no", ref.skuNo()).eq("deleted", 0)
                    .eq("version", version).set("version", version + 1)
                    .set("promotion_eligible", request.promotionEligible()).set("tax_class", request.taxClass().name())
                    .set("updated_at", now).set("updated_by", context.actorNo());
            if (DataScopeContext.executeWithoutScope(() -> policies.update(null, update)) != 1) throw conflict();
        }
        return PolicyVO.from(policy(scope, ref, row(scope, ref)));
    }
    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public Snapshot resolve(Scope scope, List<SkuRef> requested) {
        List<SkuRef> refs;
        try { refs = GoodsPricingPolicyFacts.request(scope, requested); }
        catch (RuntimeException invalid) { throw blocked(); }
        var result = new ArrayList<Policy>();
        for (var ref : refs) {
            owned(scope, ref);
            result.add(policy(scope, ref, row(scope, ref)));
        }
        return GoodsPricingPolicyFacts.freeze(scope, result);
    }
    private void owned(Scope scope, SkuRef ref) {
        var sku = DataScopeContext.executeWithoutScope(() -> skus.selectOne(new QueryWrapper<PrdSku>()
                .select("sku_no", "goods_no", "entity_no", "market")
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("goods_no", ref.goodsNo())
                .eq("sku_no", ref.skuNo()).eq("market", "CN").eq("deleted", 0)));
        var owner = DataScopeContext.executeWithoutScope(() -> goods.selectOne(new QueryWrapper<PrdGoods>()
                .select("goods_no", "entity_no", "store_no")
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .eq("goods_no", ref.goodsNo()).eq("deleted", 0)));
        if (sku == null || owner == null || !ref.goodsNo().equals(sku.getGoodsNo())
                || !ref.skuNo().equals(sku.getSkuNo()) || !scope.entityNo().equals(sku.getEntityNo())
                || !"CN".equals(sku.getMarket()) || !ref.goodsNo().equals(owner.getGoodsNo())
                || !scope.entityNo().equals(owner.getEntityNo()) || !scope.storeNo().equals(owner.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }
    private PrdSkuPricingPolicy row(Scope scope, SkuRef ref) {
        return DataScopeContext.executeWithoutScope(() -> policies.selectOne(new QueryWrapper<PrdSkuPricingPolicy>()
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .eq("goods_no", ref.goodsNo()).eq("sku_no", ref.skuNo()).eq("deleted", 0)));
    }
    private Policy policy(Scope scope, SkuRef ref, PrdSkuPricingPolicy row) {
        try {
            if (row == null || !scope.tenantNo().equals(row.getTenantNo())
                    || !scope.entityNo().equals(row.getEntityNo()) || !scope.storeNo().equals(row.getStoreNo())
                    || !ref.goodsNo().equals(row.getGoodsNo()) || !ref.skuNo().equals(row.getSkuNo())
                    || row.getPromotionEligible() == null || row.getVersion() == null) {
                throw new IllegalArgumentException();
            }
            var value = new Policy(row.getPolicyNo(), row.getGoodsNo(), row.getSkuNo(), row.getVersion(),
                    row.getPromotionEligible(), TaxClass.valueOf(row.getTaxClass()));
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
