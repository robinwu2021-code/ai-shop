package ai.neargo.shop.commerce.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.commerce.policy.ChannelPreparationPolicy;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Prepared;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Reference;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Status;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class ChannelPreparationPolicyTest {
    private final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    private final Reference reference = new Reference("IMP", "ENTITY", "STORE", "HASH");

    @Test
    void aLegacyReceiptCannotAuthorizeASupplierSend() {
        assertFalse(
                ChannelPreparationPolicy.ready(
                        new Prepared(reference, Status.PREPARED, "RECEIPT"), reference, now));
        assertFalse(ChannelPreparationPolicy.permitsSend(now.plusSeconds(120), now));
        assertFalse(ChannelPreparationPolicy.permitsSend(now.minusSeconds(1), now));
        assertTrue(
                ChannelPreparationPolicy.ready(
                        new Prepared(reference, Status.PREPARED, "RECEIPT", now.plusSeconds(121)),
                        reference,
                        now));
    }

    @Test
    void aValidDeadlineDoesNotAuthorizeAnotherImportOrAnUnpreparedOwner() {
        var proof = new Prepared(reference, Status.PREPARED, "RECEIPT", now.plusSeconds(600));
        assertFalse(
                ChannelPreparationPolicy.ready(
                        proof, new Reference("OTHER", "ENTITY", "STORE", "HASH"), now));
        assertFalse(
                ChannelPreparationPolicy.ready(
                        new Prepared(
                                reference, Status.WAITING_OWNER, "RECEIPT", now.plusSeconds(600)),
                        reference,
                        now));
        assertFalse(
                ChannelPreparationPolicy.ready(
                        new Prepared(reference, Status.CANCELLED, "RECEIPT", now.plusSeconds(600)),
                        reference,
                        now));
    }
}
