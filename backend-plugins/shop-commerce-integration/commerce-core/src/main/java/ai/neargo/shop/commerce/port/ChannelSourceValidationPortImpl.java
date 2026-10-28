package ai.neargo.shop.commerce.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable industry routing cannot resolve an absent owner through another industry's provider. */
public final class ChannelSourceValidationPortImpl implements ChannelSourceValidationPort {
    private final Map<Owner, ChannelSourceValidationProvider> providers;
    private final Clock clock;

    /** Duplicate ownership is a deployment defect, not a runtime selection priority. */
    public ChannelSourceValidationPortImpl(List<ChannelSourceValidationProvider> sources, Clock clock) {
        var byOwner = new EnumMap<Owner, ChannelSourceValidationProvider>(Owner.class);
        for (var source : sources) {
            if (source == null || source.owner() == null || byOwner.putIfAbsent(source.owner(), source) != null)
                throw new IllegalStateException("Duplicate or missing source-validation owner");
        }
        providers = Map.copyOf(byOwner);
        this.clock = clock;
    }

    @Override
    public Validation validate(Source source) {
        if (source == null || !key(source.entityNo(), 32) || !key(source.storeNo(), 32)
                || source.owner() == null || source.kind() == null || !key(source.sourceRef(), 128)
                || !key(source.sourceRevision(), 128)
                || source.salesOfferRef() != null && !key(source.salesOfferRef(), 128))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var provider = providers.get(source.owner());
        if (provider == null) return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
        var result = provider.validate(source);
        if (result == null || result.status() == null) throw invalid();
        if (result.status() == Status.VALID) {
            var now = clock.instant();
            if (!source.sourceRef().equals(result.canonicalRef())
                    || !source.sourceRevision().equals(result.canonicalRevision())
                    || !Objects.equals(source.salesOfferRef(), result.canonicalSalesOfferRef())
                    || result.contentHash() == null || !result.contentHash().matches("[a-f0-9]{64}")
                    || !key(result.ownerReceiptNo(), 128) || result.validUntil() == null
                    || !result.validUntil().isAfter(now) || result.validUntil().isAfter(now.plusSeconds(30)))
                throw invalid();
        }
        return result;
    }

    private static boolean key(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Invalid original source-validation result");
    }
}
