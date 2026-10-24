package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.service.PricingSourceService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** No owner fallback and no cached grants: every confirmation reads through the original authority again. */
@Service
public class PricingSourceServiceImpl implements PricingSourceService {
    private final List<PricingSourceFactsPort> sources;
    public PricingSourceServiceImpl(List<PricingSourceFactsPort> sources) { this.sources = List.copyOf(sources); }

    @Override public Snapshot staff(Scope scope, PricingSourceRef source) {
        if (scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = owner(source).staff(scope, source);
        return receipt(result, scope, source, null);
    }
    @Override public Snapshot customer(PricingSourceRef source, Credentials credentials) {
        if (credentials == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = owner(source).customer(source, credentials);
        if (result == null || result.subjectNo() == null || credentials.sessionNo() != null
                && !credentials.sessionNo().equals(result.subjectNo())) throw unsupported();
        return receipt(result, null, source, result.subjectNo());
    }
    @Override public Snapshot confirmStaff(Scope scope, PricingSourceRef source, String expectedSnapshotHash) {
        hash(expectedSnapshotHash);
        return same(staff(scope, source), expectedSnapshotHash);
    }
    @Override public Snapshot confirmCustomer(PricingSourceRef source, Credentials access,
            String expectedSnapshotHash) {
        hash(expectedSnapshotHash);
        return same(customer(source, access), expectedSnapshotHash);
    }
    private PricingSourceFactsPort owner(PricingSourceRef source) {
        if (source == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var matched = sources.stream().filter(s -> source.sourceSystem().equals(s.sourceSystem())).toList();
        if (matched.size() != 1) throw unsupported();
        return matched.getFirst();
    }
    private static Snapshot receipt(Snapshot result, Scope scope, PricingSourceRef source, String subject) {
        try { return PricingSourceFacts.expected(result, scope, source, subject); }
        catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) { throw unsupported(); }
    }
    private static Snapshot same(Snapshot result, String expected) {
        if (!Objects.equals(expected, result.snapshotHash())) throw BizException.of(ErrorCode.CONFLICT);
        return result;
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
