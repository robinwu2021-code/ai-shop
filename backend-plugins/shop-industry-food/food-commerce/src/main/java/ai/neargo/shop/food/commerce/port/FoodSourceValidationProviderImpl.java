package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** The original FOOD service owns current publication and market qualification, including remote-service failures. */
public final class FoodSourceValidationProviderImpl implements ChannelSourceValidationProvider {
    private final Supplier<ConfigRevisionService> menus;
    private final ObjectMapper json;
    private final Clock clock;

    /** Lazy original-service resolution avoids constructing a cross-domain publication cycle during registration. */
    public FoodSourceValidationProviderImpl(Supplier<ConfigRevisionService> menus, ObjectMapper json, Clock clock) {
        this.menus = menus;
        this.json = json;
        this.clock = clock;
    }

    @Override public Owner owner() { return Owner.FOOD; }

    @Override
    public Validation validate(Source source) {
        if (source == null || source.owner() == null || source.kind() == null
                || !key(source.entityNo(), 32) || !key(source.storeNo(), 32)
                || !key(source.sourceRef(), 128) || !key(source.sourceRevision(), 128)
                || source.salesOfferRef() != null && !key(source.salesOfferRef(), 128))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (source.owner() != Owner.FOOD || source.kind() != ChannelSourceValidationPort.Kind.MENU
                || source.salesOfferRef() != null)
            return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
        var query = new Query(source.entityNo(), source.storeNo(), source.sourceRef(), source.sourceRevision(),
                Kind.MENU, source.sourceRef(), null, null, Mode.CURRENT);
        MenuSourcePolicy.validateQuery(query);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Original source validation cannot join a local transaction");
        var owner = menus.get();
        var current = owner.resolveRevision(source.entityNo(), source.storeNo(), "MENU", source.sourceRef(),
                OffsetDateTime.now(clock));
        if (current == null || current.publication() == null)
            throw new IllegalStateException("Missing original current menu");
        var meta = current.publication();
        var currentQuery = new Query(source.entityNo(), source.storeNo(), source.sourceRef(), meta.revisionNo(),
                Kind.MENU, source.sourceRef(), null, null, Mode.CURRENT);
        MenuSourcePolicy.project(json, currentQuery, current);
        if (meta.effectiveAt().isAfter(OffsetDateTime.now(clock)))
            throw new IllegalStateException("Original current menu is not yet effective");
        if (!source.sourceRevision().equals(meta.revisionNo()))
            return new Validation(Status.STALE, null, null, null, null, null);
        var proof = owner.verifyMenuResource(query);
        MenuSourcePolicy.validateProof(json, query, proof, OffsetDateTime.now(clock));
        if (!current.equals(proof.publication())) throw BizException.of(ErrorCode.CONFLICT);
        return new Validation(Status.VALID, source.sourceRef(), source.sourceRevision(), meta.contentHash(),
                proof.validUntil().toInstant(), proof.receiptNo());
    }
    private static boolean key(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

}
