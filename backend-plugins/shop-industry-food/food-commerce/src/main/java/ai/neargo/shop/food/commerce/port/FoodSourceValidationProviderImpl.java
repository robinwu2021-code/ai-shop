package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * The original FOOD service owns current publication and market qualification, including
 * remote-service failures.
 */
public final class FoodSourceValidationProviderImpl implements ChannelSourceValidationProvider {
    private final Supplier<ConfigRevisionService> menus;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * Lazy original-service resolution avoids constructing a cross-domain publication cycle during
     * registration.
     */
    public FoodSourceValidationProviderImpl(
            Supplier<ConfigRevisionService> menus, ObjectMapper json, Clock clock) {
        this.menus = menus;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Validation validate(Source source) {
        CatalogSourceTransportPolicy.validateSource(source);
        if (source.owner() != Owner.FOOD)
            return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
        Kind kind;
        try {
            kind = Kind.valueOf(source.kind().name());
        } catch (IllegalArgumentException unsupported) {
            return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
        }
        var context = source.context();
        boolean root = kind == Kind.MENU;
        if (!root && context == null || root && source.salesOfferRef() != null)
            return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
        var menu = context == null ? source.sourceRef() : context.menuRef();
        if (root && !source.sourceRef().equals(menu)) throw BizException.of(ErrorCode.BAD_REQUEST);
        var mode = context == null ? Mode.CURRENT : Mode.valueOf(context.mode().name());
        var query =
                new Query(
                        source.entityNo(),
                        source.storeNo(),
                        menu,
                        source.sourceRevision(),
                        kind,
                        source.sourceRef(),
                        source.salesOfferRef(),
                        context == null ? null : context.optionGroupRef(),
                        mode,
                        context == null ? null : context.variantSkuRef(),
                        context == null ? null : context.bundleGroupRef());
        MenuSourcePolicy.validateQuery(query);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Original source validation cannot join a local transaction");
        var owner = menus.get();
        ConfigRevisionContentVO current = null;
        if (mode == Mode.CURRENT) {
            current =
                    owner.resolveRevision(
                            source.entityNo(),
                            source.storeNo(),
                            "MENU",
                            menu,
                            OffsetDateTime.now(clock));
            if (current == null || current.publication() == null)
                throw new IllegalStateException("Missing original current menu");
            var meta = current.publication();
            var currentQuery =
                    new Query(
                            source.entityNo(),
                            source.storeNo(),
                            menu,
                            meta.revisionNo(),
                            Kind.MENU,
                            menu,
                            null,
                            null,
                            Mode.CURRENT);
            MenuSourcePolicy.project(json, currentQuery, current);
            if (meta.effectiveAt().isAfter(OffsetDateTime.now(clock)))
                throw new IllegalStateException("Original current menu is not yet effective");
            if (!source.sourceRevision().equals(meta.revisionNo()))
                return new Validation(Status.STALE, null, null, null, null, null);
        }
        var proof = owner.verifyMenuResource(query);
        MenuSourcePolicy.validateProof(json, query, proof, OffsetDateTime.now(clock));
        if (current != null && !current.equals(proof.publication()))
            throw BizException.of(ErrorCode.CONFLICT);
        return new Validation(
                Status.VALID,
                source.sourceRef(),
                source.sourceRevision(),
                proof.publication().publication().contentHash(),
                proof.validUntil().toInstant(),
                proof.receiptNo(),
                source.salesOfferRef(),
                source.context());
    }

    @Override
    public Validation current(Source source) {
        CatalogSourceTransportPolicy.validateSource(source);
        if (source.owner() != Owner.FOOD
                || source.kind() != ChannelSourceValidationPort.Kind.MENU
                || source.salesOfferRef() != null
                || source.context() != null)
            return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Original source current lookup cannot join a transaction");
        var head =
                menus.get()
                        .resolveRevision(
                                source.entityNo(),
                                source.storeNo(),
                                "MENU",
                                source.sourceRef(),
                                OffsetDateTime.now(clock));
        if (head == null || head.publication() == null)
            throw new IllegalStateException("Missing original menu head");
        return validate(
                new Source(
                        source.entityNo(),
                        source.storeNo(),
                        source.owner(),
                        source.kind(),
                        source.sourceRef(),
                        head.publication().revisionNo(),
                        null));
    }
}
