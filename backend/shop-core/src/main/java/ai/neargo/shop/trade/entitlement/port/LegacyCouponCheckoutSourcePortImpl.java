package ai.neargo.shop.trade.entitlement.port;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort;
import ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Read-only SPI adapter is separate from the core writer service in embedded and reverse-HTTP deployments. */
@Component
public class LegacyCouponCheckoutSourcePortImpl implements LegacyCouponCheckoutSourcePort {
    private final LegacyCouponCheckoutJournal journal;
    public LegacyCouponCheckoutSourcePortImpl(LegacyCouponCheckoutJournal journal) {this.journal=journal;}
    @Override public CheckoutFact resolve(EntitlementContext ctx,String checkout) {return journal.resolve(ctx,checkout);}
    @Override public Optional<Decision> decision(EntitlementContext ctx,String checkout) {return journal.decision(ctx,checkout);}
    @Override public Optional<Reversal> reversal(EntitlementContext ctx,String checkout) {return journal.reversal(ctx,checkout);}
}
