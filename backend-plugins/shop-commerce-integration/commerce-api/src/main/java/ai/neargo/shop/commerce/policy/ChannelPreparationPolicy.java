package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Prepared;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Reference;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Status;

import java.time.Duration;
import java.time.Instant;

/**
 * Only the original owner can extend readiness; a cached receipt never extends its own deadline.
 */
public final class ChannelPreparationPolicy {
    public static final Duration SEND_LEASE = Duration.ofMinutes(2);

    private ChannelPreparationPolicy() {}

    /** Requires enough original validity for the complete outbound claim window. */
    public static boolean permitsSend(Instant validUntil, Instant now) {
        return validUntil != null && now != null && validUntil.isAfter(now.plus(SEND_LEASE));
    }

    /** Checks immutable import identity before a readiness receipt is accepted. */
    public static boolean ready(Prepared prepared, Reference reference, Instant now) {
        if (prepared == null
                || reference == null
                || !reference.equals(prepared.reference())
                || prepared.status() != Status.PREPARED
                || !permitsSend(prepared.validUntil(), now)) {
            return false;
        }
        CommerceValidation.text(prepared.preparationReceiptRef(), 256);
        return true;
    }
}
