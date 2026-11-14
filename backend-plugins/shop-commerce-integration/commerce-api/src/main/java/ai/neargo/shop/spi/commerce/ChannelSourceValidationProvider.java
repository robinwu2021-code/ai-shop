package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

/**
 * Industry evidence is contributed separately so only one consumer Port is visible to the channel
 * core.
 */
public interface ChannelSourceValidationProvider {
    /**
     * A provider owns exactly one stable industry; it cannot serve as another industry's fallback.
     */
    Owner owner();

    /** Original-owner evidence grants no stock, price, payment or fulfilment authorization. */
    Validation validate(Source source);

    /**
     * Resolve current revision only through the original owner; old providers cannot infer a new
     * head.
     */
    default Validation current(Source source) {
        return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
    }
}
