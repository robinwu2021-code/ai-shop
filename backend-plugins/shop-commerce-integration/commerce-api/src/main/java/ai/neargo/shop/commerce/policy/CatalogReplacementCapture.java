package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogReplacement;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Genuine owner observations are reread before an explicit replacement; no caller-provided proof is
 * trusted on its own.
 */
public final class CatalogReplacementCapture {
    private CatalogReplacementCapture() {}

    public static Document enrich(
            CaptureCommand command,
            Document original,
            ChannelCatalogAuthorityService authorities,
            CatalogPriceSourcePort prices,
            ChannelSourceWithdrawalProvider withdrawals,
            ObjectMapper json,
            Clock clock) {
        CatalogSourceTransportPolicy.validateCommand(command, clock);
        if (prices == null)
            throw new IllegalStateException("Original scope authority is unavailable");
        var deadlines = new ArrayList<Instant>();
        deadlines.add(clock.instant().plusSeconds(30));
        var aq =
                new ChannelCatalogAuthorityContracts.Query(
                        command.entityNo(),
                        command.storeNo(),
                        command.connectionNo(),
                        command.connectionRevisionNo());
        var authority = authorities.capture(aq);
        ChannelCatalogAuthorityPolicy.validate(aq, authority, clock.instant());
        var scope = new CatalogPriceSourcePort.MarketScope(command.entityNo(), command.storeNo());
        var facts = prices.scope(scope);
        CatalogPriceSourceFacts.validateScopeFacts(scope, facts, clock.instant());
        require(
                command.owner().name().equals(authority.connection().sourceOwner())
                        && facts.market().market().equals(authority.profile().market()));
        deadlines.add(authority.validUntil());
        deadlines.add(facts.validUntil());
        var proven = new ArrayList<ChannelSourceWithdrawalProvider.Facts>();
        if (command.withdrawals() != null)
            for (var expected : command.withdrawals()) {
                require(withdrawals != null && withdrawals.owner() == command.owner());
                var actual = withdrawals.capture(expected.data().query());
                SourceWithdrawalPolicy.validate(expected.data().query(), actual, clock);
                require(expected.data().equals(actual.data()));
                deadlines.add(expected.validUntil());
                deadlines.add(actual.validUntil());
                proven.add(actual);
            }
        for (var expected : proven) {
            var after = withdrawals.capture(expected.data().query());
            SourceWithdrawalPolicy.validate(expected.data().query(), after, clock);
            require(expected.data().equals(after.data()));
            deadlines.add(after.validUntil());
        }
        var after = prices.scope(scope);
        CatalogPriceSourceFacts.validateScopeFacts(scope, after, clock.instant());
        require(facts.market().equals(after.market()) && facts.tax().equals(after.tax()));
        deadlines.add(after.validUntil());
        var latest = authorities.capture(aq);
        ChannelCatalogAuthorityPolicy.validate(aq, latest, clock.instant());
        require(
                authority.provider().equals(latest.provider())
                        && authority.profile().equals(latest.profile())
                        && authority.account().equals(latest.account())
                        && authority.connection().equals(latest.connection())
                        && authority.grant().equals(latest.grant()));
        deadlines.add(latest.validUntil());
        if (original != null) {
            require(command.sources().equals(original.sources()));
            deadlines.add(original.validUntil());
        }
        command.sources().forEach(s -> deadlines.add(s.proof().validUntil()));
        var until = deadlines.stream().min(Instant::compareTo).orElseThrow();
        require(until.isAfter(clock.instant()));
        var details =
                original == null
                        ? new ChannelCatalogDetails(List.of(), List.of(), List.of())
                        : original.details();
        if (original == null) require(command.sources().isEmpty() && !proven.isEmpty());
        var start = clock.instant().truncatedTo(ChronoUnit.DAYS);
        var availability =
                original == null
                        ? new CatalogAvailability(start, start.plus(7, ChronoUnit.DAYS), List.of())
                        : original.availability();
        String taxRef =
                "TAX:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        command.entityNo(),
                                        command.storeNo(),
                                        facts.market(),
                                        facts.tax()));
        var basis =
                new CatalogTaxBasis(
                        taxRef, command.entityNo(), command.storeNo(), facts.market(), facts.tax());
        var taxBases = details.offers().isEmpty() ? List.of(basis) : original.taxBases();
        var replacement = new CatalogReplacement(facts, latest, proven);
        var document =
                new Document(
                        command.owner(),
                        facts.market().currency(),
                        command.sources(),
                        List.of(),
                        List.of(),
                        List.of(),
                        until,
                        PricingSnapshotHash.of(
                                List.of(
                                        command,
                                        facts.tax(),
                                        proven.stream().map(p -> p.data()).toList(),
                                        until)),
                        3,
                        details,
                        availability,
                        taxBases,
                        replacement);
        return CatalogSourceTransportPolicy.validateDocument(command, document, json, clock);
    }

    private static void require(boolean value) {
        CatalogDocumentPolicy.require(value);
    }
}
