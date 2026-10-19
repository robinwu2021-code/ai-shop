package ai.neargo.shop.product.entitlement;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.CardProductBindingView;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.product.entity.PrdEntBindingCommand;
import ai.neargo.shop.product.entity.PrdEntitlementBinding;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.PrdEntBindingCommandMapper;
import ai.neargo.shop.product.mapper.PrdEntitlementBindingMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.PublishedDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** All external publication/market checks happen before entering this core transaction. */
@Component
public class CardProductBindingWriter {
    private final PrdEntitlementBindingMapper bindings;
    private final PrdEntBindingCommandMapper commands;
    private final SkuMapper skus;
    private final ObjectMapper json;
    public CardProductBindingWriter(PrdEntitlementBindingMapper bindings, PrdEntBindingCommandMapper commands,
                                     SkuMapper skus, ObjectMapper json) {
        this.bindings = bindings; this.commands = commands; this.skus = skus; this.json = json;
    }
    public CardProductBindingView replay(EntitlementContext ctx, String entity,
                                         ProductEntitlementBinding c, String key) {
        String hash = CardBindingFingerprint.payload(ctx, entity, c, key, json);
        var previous = commands.find(ctx.tenantNo(), key);
        return previous == null ? null : original(previous, hash);
    }
    @Transactional
    public CardProductBindingView write(EntitlementContext ctx, String entity, ProductEntitlementBinding c, String key,
                                         String market, String currency, PublishedDefinition definition) {
        return DataScopeContext.executeWithoutScope(() ->
                writeQualified(ctx, entity, c, key, market, currency, definition));
    }
    private CardProductBindingView writeQualified(EntitlementContext ctx, String entity, ProductEntitlementBinding c,
                                                   String key, String market, String currency,
                                                   PublishedDefinition definition) {
        String hash = CardBindingFingerprint.payload(ctx, entity, c, key, json);
        var claim = new PrdEntBindingCommand(); audit(claim, ctx);
        claim.setRequestKey(key); claim.setPayloadHash(hash); claim.setStatus("PENDING"); commands.claim(claim);
        var command = commands.lock(ctx.tenantNo(), key);
        if (command == null || !hash.equals(command.getPayloadHash())) conflict();
        var replay = original(command, hash);
        if (replay != null) return replay;
        var product = bindings.lockGoods(ctx.tenantNo(), entity, c.goodsNo());
        if (product == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!PrdGoods.TYPE_CARD.equals(product.getType())
                || product.getVersion() != c.expectedProductRevision()) conflict();
        var sku = skus.selectOne(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getTenantNo, ctx.tenantNo())
                .eq(PrdSku::getEntityNo, entity).eq(PrdSku::getGoodsNo, c.goodsNo()).eq(PrdSku::getSkuNo, c.skuNo())
                .eq(PrdSku::getMarket, market).last("FOR UPDATE"));
        if (sku == null || sku.getPrice() == null || sku.getPrice() <= 0) conflict();
        var row = bindings.selectOne(Wrappers.<PrdEntitlementBinding>lambdaQuery()
                .eq(PrdEntitlementBinding::getTenantNo, ctx.tenantNo())
                .eq(PrdEntitlementBinding::getGoodsNo, c.goodsNo())
                .eq(PrdEntitlementBinding::getSkuNo, c.skuNo()).last("FOR UPDATE"));
        boolean fresh = row == null;
        if (fresh) { row = new PrdEntitlementBinding(); audit(row, ctx); row.setVersion(0L); }
        row.setGoodsNo(c.goodsNo()); row.setSkuNo(c.skuNo()); row.setMarket(market);
        if (!c.templateVersionNo().equals(definition.versionNo())
                || !"PAID_CARD".equals(definition.definition().kind())
                || !currency.equals(definition.definition().currency())) conflict();
        row.setTemplateVersionNo(c.templateVersionNo()); row.setUnitsPerItem(c.unitsPerItem());
        row.setRuleSnapshot(json.writeValueAsString(definition.definition()));
        row.setRuleHash(definition.snapshotHash());
        row.setCurrency(currency); row.setStatus("ACTIVE"); row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(ctx.actorNo());
        if ((fresh ? bindings.insert(row) : bindings.updateById(row)) != 1) conflict();
        if (bindings.bumpProduct(ctx.tenantNo(), entity, c.goodsNo(), c.expectedProductRevision(),
                LocalDateTime.now(ZoneOffset.UTC), ctx.actorNo()) != 1) conflict();
        var result = new CardProductBindingView(c.goodsNo(), c.skuNo(), c.templateVersionNo(), c.unitsPerItem(),
                currency, "ACTIVE", c.expectedProductRevision() + 1, row.getVersion());
        command.setStatus("COMPLETED"); command.setResponseSnapshot(json.writeValueAsString(result));
        command.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC)); command.setUpdatedBy(ctx.actorNo());
        if (commands.updateById(command) != 1) conflict();
        return result;
    }
    private CardProductBindingView original(PrdEntBindingCommand row, String hash) {
        if (!hash.equals(row.getPayloadHash())) conflict();
        return "COMPLETED".equals(row.getStatus())
                ? json.readValue(row.getResponseSnapshot(), CardProductBindingView.class) : null;
    }
    private void audit(BaseEntity row, EntitlementContext ctx) {
        row.setTenantNo(ctx.tenantNo()); row.setCreatedBy(ctx.actorNo()); row.setUpdatedBy(ctx.actorNo());
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
