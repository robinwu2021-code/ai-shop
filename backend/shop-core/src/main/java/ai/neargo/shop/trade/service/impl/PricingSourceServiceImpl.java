package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.service.PricingSourceService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * No owner fallback and no cached grants: every confirmation reads through the original authority
 * again.
 */
@Service
public class PricingSourceServiceImpl implements PricingSourceService {
    private final List<PricingSourceFactsPort> sources;

    public PricingSourceServiceImpl(List<PricingSourceFactsPort> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public Party party(Command command) {
        if (command == null || command.identity() == null || command.identity().partyNo() == null)
            throw unsupported();
        var result = owner(command.source()).party(command);
        var id = command.identity();
        if (result == null
                || !new Scope(id.tenantNo(), id.entityNo(), id.storeNo()).equals(result.scope())
                || !id.partyNo().equals(result.partyNo())
                || !command.source().sourceNo().equals(result.sourceNo())
                || command.source().sourceVersion() != result.sourceVersion()
                || !Set.of("GUEST", "DEVICE").contains(result.kind())) throw unsupported();
        return result;
    }

    @Override
    public Plan demandPlan(Command command, String orderNo, String subOrderNo) {
        var result = owner(command.source()).demandPlan(command, orderNo, subOrderNo);
        if (result != null) {
            try {
                InventoryDemandPlanFacts.validate(result);
            } catch (RuntimeException invalid) {
                throw unsupported();
            }
            if (!command.identity().tenantNo().equals(result.scope().tenantNo())
                    || !command.identity().entityNo().equals(result.scope().entityNo())
                    || !orderNo.equals(result.scope().externalRef())) throw unsupported();
            for (var branch : result.branches()) {
                if (!command.identity().storeNo().equals(branch.storeNo())
                        || !subOrderNo.equals(branch.fulfillmentRef())) throw unsupported();
            }
        }
        return result;
    }

    @Override
    public Delivery delivery(Command command) {
        var delivery = owner(command.source()).delivery(command);
        var id = command.identity();
        if (delivery == null
                || !new Scope(id.tenantNo(), id.entityNo(), id.storeNo()).equals(delivery.scope())
                || !command.source().sourceNo().equals(delivery.sourceNo())
                || command.source().sourceVersion() != delivery.sourceVersion()
                || delivery.revision() < 0
                || !bounded(delivery.receiverName(), 64)
                || !bounded(delivery.receiverPhone(), 32)
                || !bounded(delivery.address(), 512)
                || (delivery.latE6() == null) != (delivery.lngE6() == null)
                || delivery.latE6() != null
                        && (Math.abs((long) delivery.latE6()) > 90_000_000
                                || Math.abs((long) delivery.lngE6()) > 180_000_000)
                || delivery.deliveryInstruction() != null
                        && (delivery.deliveryInstruction().length() > 512
                                || delivery.deliveryInstruction()
                                        .codePoints()
                                        .anyMatch(Character::isISOControl))) throw unsupported();
        return delivery;
    }

    private static boolean bounded(String text, int max) {
        return text != null
                && !text.isBlank()
                && text.length() <= max
                && text.codePoints().noneMatch(Character::isISOControl);
    }

    @Override
    public Snapshot staff(Scope scope, PricingSourceRef source) {
        if (scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = owner(source).staff(scope, source);
        return receipt(result, scope, source, null);
    }

    @Override
    public Snapshot cancellationForStore(Command command, String key, String fulfillment) {
        if (command == null || command.identity() == null)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var actor = command.identity();
        var scope = new Scope(actor.tenantNo(), actor.entityNo(), actor.storeNo());
        var result = owner(command.source()).cancellationForStore(command, key, fulfillment);
        return receipt(result, scope, command.source(), null);
    }

    @Override
    public Snapshot customer(PricingSourceRef source, Credentials credentials) {
        if (credentials == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = owner(source).customer(source, credentials);
        if (result == null
                || result.subjectNo() == null
                || credentials.sessionNo() != null
                        && !credentials.sessionNo().equals(result.subjectNo())) throw unsupported();
        return receipt(result, null, source, result.subjectNo());
    }

    @Override
    public Snapshot confirmStaff(
            Scope scope, PricingSourceRef source, String expectedSnapshotHash) {
        hash(expectedSnapshotHash);
        return same(staff(scope, source), expectedSnapshotHash);
    }

    @Override
    public Snapshot confirmCustomer(
            PricingSourceRef source, Credentials access, String expectedSnapshotHash) {
        hash(expectedSnapshotHash);
        return same(customer(source, access), expectedSnapshotHash);
    }

    private PricingSourceFactsPort owner(PricingSourceRef source) {
        if (source == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var matched =
                sources.stream()
                        .filter(s -> source.sourceSystem().equals(s.sourceSystem()))
                        .toList();
        if (matched.size() != 1) throw unsupported();
        return matched.getFirst();
    }

    private static Snapshot receipt(
            Snapshot result, Scope scope, PricingSourceRef source, String subject) {
        try {
            return PricingSourceFacts.expected(result, scope, source, subject);
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) {
            throw unsupported();
        }
    }

    private static Snapshot same(Snapshot result, String expected) {
        if (!Objects.equals(expected, result.snapshotHash()))
            throw BizException.of(ErrorCode.CONFLICT);
        return result;
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException unsupported() {
        return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}
