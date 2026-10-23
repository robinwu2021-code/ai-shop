package ai.neargo.shop.trade.port;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.service.PricingTaxSettingService;
import org.springframework.stereotype.Component;

/** Cross-industry adapter validates actual store ownership before deriving the merchant setting key. */
@Component
public class PricingTaxSettingPortImpl implements PricingTaxSettingPort {
    private final PricingTaxSettingService settings;
    private final StoreDirectoryPort stores;

    public PricingTaxSettingPortImpl(PricingTaxSettingService settings, StoreDirectoryPort stores) {
        this.settings = settings;
        this.stores = stores;
    }

    @Override
    public MerchantTaxSnapshot resolve(Scope scope) {
        if (scope == null || !"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var card = stores.cards(List.of(scope.storeNo())).get(scope.storeNo());
        if (card == null || !scope.storeNo().equals(card.storeNo()) || !scope.entityNo().equals(card.entityNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var context = new Context(scope.tenantNo(), "TAX_SOURCE", false, scope.entityNo(), scope.storeNo(),
                new PricingCapabilities(0, 0, false, false, false), "TAX_READ_ONLY", Instant.EPOCH, Set.of());
        var setting = settings.getMerchant(context);
        var source = setting.effective();
        if (source == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return new MerchantTaxSnapshot(scope.tenantNo(), scope.entityNo(), scope.storeNo(), setting.version(),
                Source.valueOf(source.source().name()), source.ownerNo(), source.version(), source.rateBp(),
                source.priceTaxMode(), source.contentHash());
    }
}
