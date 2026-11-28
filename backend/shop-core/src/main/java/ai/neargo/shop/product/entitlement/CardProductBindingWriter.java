package ai.neargo.shop.product.entitlement;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.product.dto.CardProductBindingView;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.PublishedDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * All external publication/market checks happen before entering this core transaction.
 *
 * <p>门店交易库里绑定是 {@code prd_sku} 的三列（{@code entitlement_version_no}、
 * {@code entitlement_rule_snapshot}、{@code entitlement_units}），写在商家本市场那一行上；
 * 防重走公共的 {@link RequestIdempotencyPort}，不再有专用的 prd_ent_binding_command 表。
 */
@Component
public class CardProductBindingWriter {
    static final String ENDPOINT = "CARD_PRODUCT_BINDING";
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final RequestIdempotencyPort idempotency;
    private final ObjectMapper json;
    public CardProductBindingWriter(GoodsMapper goods, SkuMapper skus, RequestIdempotencyPort idempotency,
                                    ObjectMapper json) {
        this.goods = goods; this.skus = skus; this.idempotency = idempotency; this.json = json;
    }
    /** 同一个 key：已完成则原样返回；载荷不同则冲突（由防重能力判定）。 */
    public CardProductBindingView replay(EntitlementContext ctx, String entity,
                                         ProductEntitlementBinding c, String key) {
        String hash = CardBindingFingerprint.payload(ctx, entity, c, key, json);
        return idempotency.findCompleted(key, ENDPOINT, ctx.actorNo(), hash, CardProductBindingView.class)
                .orElse(null);
    }
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public CardProductBindingView write(EntitlementContext ctx, String entity, ProductEntitlementBinding c, String key,
                                         String market, String currency, PublishedDefinition definition) {
        String hash = CardBindingFingerprint.payload(ctx, entity, c, key, json);
        return idempotency.execute(key, ENDPOINT, ctx.actorNo(), hash, CardProductBindingView.class,
                () -> DataScopeContext.executeWithoutScope(() ->
                        writeQualified(ctx, entity, c, market, currency, definition)));
    }
    private CardProductBindingView writeQualified(EntitlementContext ctx, String entity, ProductEntitlementBinding c,
                                                   String market, String currency, PublishedDefinition definition) {
        var product = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getTenantNo, ctx.tenantNo())
                .eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getGoodsNo, c.goodsNo()).last("FOR UPDATE"));
        if (product == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!PrdGoods.TYPE_CARD.equals(product.getType())
                || product.getVersion() != c.expectedProductRevision()) conflict();
        var sku = skus.selectOne(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getTenantNo, ctx.tenantNo())
                .eq(PrdSku::getEntityNo, entity).eq(PrdSku::getGoodsNo, c.goodsNo()).eq(PrdSku::getSkuNo, c.skuNo())
                .eq(PrdSku::getMarket, market).last("FOR UPDATE"));
        if (sku == null || sku.getPrice() == null || sku.getPrice() <= 0) conflict();
        if (!c.templateVersionNo().equals(definition.versionNo())
                || !"PAID_CARD".equals(definition.definition().kind())
                || !currency.equals(definition.definition().currency())) conflict();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        long skuVersion = sku.getVersion() == null ? 0 : sku.getVersion();
        if (skus.update(null, Wrappers.<PrdSku>lambdaUpdate().eq(PrdSku::getId, sku.getId())
                .eq(PrdSku::getVersion, skuVersion)
                .set(PrdSku::getVersion, skuVersion + 1)
                .set(PrdSku::getEntitlementVersionNo, c.templateVersionNo())
                .set(PrdSku::getEntitlementRuleSnapshot, json.writeValueAsString(definition.definition()))
                .set(PrdSku::getEntitlementUnits, c.unitsPerItem())
                .set(PrdSku::getUpdatedAt, now).set(PrdSku::getUpdatedBy, ctx.actorNo())) != 1) conflict();
        if (goods.update(null, Wrappers.<PrdGoods>lambdaUpdate().eq(PrdGoods::getTenantNo, ctx.tenantNo())
                .eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getGoodsNo, c.goodsNo())
                .eq(PrdGoods::getVersion, c.expectedProductRevision())
                .set(PrdGoods::getVersion, c.expectedProductRevision() + 1)
                .set(PrdGoods::getUpdatedAt, now).set(PrdGoods::getUpdatedBy, ctx.actorNo())) != 1) conflict();
        return new CardProductBindingView(c.goodsNo(), c.skuNo(), c.templateVersionNo(), c.unitsPerItem(),
                currency, PrdSku.STATUS_ACTIVE, c.expectedProductRevision() + 1, skuVersion + 1);
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
