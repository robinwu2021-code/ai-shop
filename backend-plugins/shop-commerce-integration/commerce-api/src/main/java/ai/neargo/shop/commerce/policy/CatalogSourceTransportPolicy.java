package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;

/**
 * Both HTTP sides reject coercion and incomplete evidence using the same original catalog policies.
 */
public final class CatalogSourceTransportPolicy {
    private CatalogSourceTransportPolicy() {}

    /**
     * An internal caller cannot turn arbitrary JSON fields or numeric enum ordinals into authorized
     * scope.
     */
    public static <T> T read(ObjectMapper json, byte[] body, Class<T> type) {
        if (body == null || body.length == 0 || body.length > 4 * 1024 * 1024) bad();
        return json.readerFor(type)
                .with(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(body);
    }

    public static void validateCommand(CaptureCommand c, Clock clock) {
        if (c == null
                || c.owner() == null
                || c.sources() == null
                || c.sources().isEmpty() && (c.withdrawals() == null || c.withdrawals().isEmpty())
                || c.sources().size() > 100) bad();
        CommerceValidation.text(c.entityNo(), 32);
        CommerceValidation.text(c.storeNo(), 32);
        CommerceValidation.text(c.connectionNo(), 32);
        CommerceValidation.text(c.connectionRevisionNo(), 32);
        var unique = new HashSet<String>();
        for (var source : c.sources()) {
            if (source == null
                    || source.owner() != c.owner()
                    || source.priority() < 0
                    || source.priority() > 10000
                    || !unique.add(source.sourceRef())
                    || source.proof() == null
                    || source.proof().status() != Status.VALID) bad();
            var expected =
                    new Source(
                            c.entityNo(),
                            c.storeNo(),
                            c.owner(),
                            c.owner() == Owner.FOOD ? Kind.MENU : Kind.SELECTION,
                            source.sourceRef(),
                            source.selectionRevision());
            validateSource(expected);
            validateProof(expected, source.proof(), clock);
        }
        if (c.withdrawals() != null) {
            if (c.withdrawals().size() > 100 || c.sources().size() + c.withdrawals().size() > 100)
                bad();
            for (var proof : c.withdrawals()) {
                if (proof == null || proof.data() == null) bad();
                var q = proof.data().query();
                SourceWithdrawalPolicy.validate(q, proof, clock);
                if (q.owner() != c.owner()
                        || !c.entityNo().equals(q.entityNo())
                        || !c.storeNo().equals(q.storeNo())
                        || !unique.add(q.sourceRef())) bad();
            }
        }
    }

    public static void validateSource(Source s) {
        if (s == null || s.owner() == null || s.kind() == null) bad();
        CommerceValidation.text(s.entityNo(), 32);
        CommerceValidation.text(s.storeNo(), 32);
        CommerceValidation.text(s.sourceRef(), 128);
        CommerceValidation.text(s.sourceRevision(), 128);
        if (s.salesOfferRef() != null) CommerceValidation.text(s.salesOfferRef(), 128);
        if (s.context() != null) {
            CommerceValidation.text(s.context().menuRef(), 128);
            if (s.context().mode() == null) bad();
            if (s.context().optionGroupRef() != null)
                CommerceValidation.text(s.context().optionGroupRef(), 128);
            if (s.context().variantSkuRef() != null)
                CommerceValidation.text(s.context().variantSkuRef(), 128);
            if (s.context().bundleGroupRef() != null)
                CommerceValidation.text(s.context().bundleGroupRef(), 128);
        }
    }

    public static void validateProof(Source source, Validation proof, Clock clock) {
        validateSource(source);
        if (proof == null || proof.status() == null)
            throw new IllegalStateException("Missing owner validation");
        if (proof.status() != Status.VALID) return;
        if (!source.sourceRef().equals(proof.canonicalRef())
                || !source.sourceRevision().equals(proof.canonicalRevision())
                || !Objects.equals(source.salesOfferRef(), proof.canonicalSalesOfferRef())
                || !Objects.equals(source.context(), proof.canonicalContext())
                || proof.contentHash() == null
                || !proof.contentHash().matches("[a-f0-9]{64}")
                || proof.validUntil() == null
                || !proof.validUntil().isAfter(clock.instant())
                || proof.validUntil().isAfter(clock.instant().plusSeconds(30)))
            throw new IllegalStateException("Invalid owner validation");
        CommerceValidation.text(proof.ownerReceiptNo(), 128);
    }

    public static Document validateDocument(
            CaptureCommand command, Document document, ObjectMapper json, Clock clock) {
        validateCommand(command, clock);
        var result =
                CatalogDocumentPolicy.freezeAt(document, command.sources(), json, clock.instant());
        CatalogTaxBasisPolicy.requireScope(result, command.entityNo(), command.storeNo());
        if (command.withdrawals() != null && !command.withdrawals().isEmpty()) {
            if (document.replacement() == null
                    || !command.withdrawals().stream()
                            .map(p -> p.data())
                            .toList()
                            .equals(
                                    document.replacement().withdrawals().stream()
                                            .map(p -> p.data())
                                            .toList()))
                throw new IllegalStateException("Original withdrawal set mismatch");
        } else if (document.replacement() != null
                && !document.replacement().withdrawals().isEmpty())
            throw new IllegalStateException("Unexpected withdrawal set");
        if (document.replacement() != null
                && (!command.connectionNo()
                                .equals(document.replacement().authority().query().connectionNo())
                        || !command.connectionRevisionNo()
                                .equals(document.replacement().authority().query().revisionNo())))
            throw new IllegalStateException("Wrong replacement connection authority");
        if (document.owner() != command.owner())
            throw new IllegalStateException("Wrong catalog owner");
        return result;
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
