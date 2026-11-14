package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * Exact source scope, original issuer balances and original action receipts; UNKNOWN is never zero
 * or a successful use.
 */
public final class ChannelRedemptionPolicy {
    private ChannelRedemptionPolicy() {}

    public static void verified(
            ChannelRedemptionGateway.Verify request,
            ChannelRedemptionGateway.Verified result,
            Clock clock) {
        require(
                result != null
                        && result.status() != null
                        && request.operationNo().equals(result.operationNo())
                        && PricingSnapshotHash.of(request).equals(result.requestHash()));
        if (result.status() != ChannelRedemptionGateway.Status.CONFIRMED) {
            require(result.certificate() == null);
            return;
        }
        var certificate = result.certificate();
        require(certificate != null);
        hash(certificate.certificateIdentityHash());
        require(
                certificate.productIdentity() != null
                        && certificate.productIdentity().parents() != null);
        CommerceValidation.text(certificate.productIdentity().externalRef(), 128);
        CommerceValidation.text(certificate.unit(), 32);
        quantity(certificate.totalQuantity(), false);
        quantity(certificate.consumedQuantity(), true);
        quantity(certificate.remainingQuantity(), true);
        require(
                certificate
                                        .totalQuantity()
                                        .compareTo(
                                                certificate
                                                        .consumedQuantity()
                                                        .add(certificate.remainingQuantity()))
                                == 0
                        && certificate.expiresAt() != null
                        && certificate.expiresAt().isAfter(clock.instant())
                        && certificate.validUntil() != null
                        && certificate.validUntil().isAfter(clock.instant())
                        && !certificate.validUntil().isAfter(clock.instant().plusSeconds(30))
                        && !certificate.validUntil().isAfter(certificate.expiresAt()));
        CommerceValidation.text(certificate.originalReceiptRef(), 256);
        if (certificate.externalOrderRef() != null)
            CommerceValidation.text(certificate.externalOrderRef(), 128);
    }

    public static void result(
            ChannelRedemptionGateway.Action request, ChannelRedemptionGateway.Result result) {
        require(
                result != null
                        && result.status() != null
                        && request.operationNo().equals(result.operationNo())
                        && PricingSnapshotHash.of(request).equals(result.requestHash())
                        && request.certificateIdentityHash()
                                .equals(result.certificateIdentityHash())
                        && request.unit().equals(result.unit()));
        if (result.status() == ChannelRedemptionGateway.Status.UNKNOWN) {
            require(result.appliedQuantity() == null && result.cumulativeQuantity() == null);
            return;
        }
        CommerceValidation.text(result.originalReceiptRef(), 256);
        quantity(result.appliedQuantity(), true);
        quantity(result.cumulativeQuantity(), true);
        require(
                result.status() == ChannelRedemptionGateway.Status.CONFIRMED
                        ? request.quantity().compareTo(result.appliedQuantity()) == 0
                                && result.cumulativeQuantity().compareTo(result.appliedQuantity())
                                        >= 0
                        : result.appliedQuantity().signum() == 0);
    }

    public static void quantity(BigDecimal quantity, boolean zero) {
        require(
                quantity != null
                        && (zero ? quantity.signum() >= 0 : quantity.signum() > 0)
                        && quantity.stripTrailingZeros().scale() <= 6
                        && quantity.compareTo(new BigDecimal("99999999999999.999999")) <= 0);
    }

    public static void hash(String hash) {
        require(hash != null && hash.matches("[a-f0-9]{64}"));
    }

    private static void require(boolean valid) {
        CatalogDocumentPolicy.require(valid);
    }
}
