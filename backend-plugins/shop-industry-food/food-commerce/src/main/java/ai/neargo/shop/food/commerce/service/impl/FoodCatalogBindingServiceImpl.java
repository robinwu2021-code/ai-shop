package ai.neargo.shop.food.commerce.service.impl;

import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts.Facts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts.Query;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Original-owner reads run outside a shared transaction and retain their shortest evidence window. */
public final class FoodCatalogBindingServiceImpl implements FoodCatalogBindingService {
    private final ObjectMapper json;
    private final ConfigRevisionService menus;
    private final ChannelCatalogAuthorityService authorities;
    private final Clock clock;

    /** Inject original readers and a clock so evidence freshness does not depend on deployment shape. */
    public FoodCatalogBindingServiceImpl(
            ObjectMapper json,
            ConfigRevisionService menus,
            ChannelCatalogAuthorityService authorities,
            Clock clock) {
        this.json = json;
        this.menus = menus;
        this.authorities = authorities;
        this.clock = clock;
    }

    @Override
    public Facts capture(Query query) {
        validateQuery(query);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("FOOD_CATALOG_BINDING_OUTSIDE_TRANSACTION_REQUIRED");
        }
        var started = clock.instant();
        var first = menus.verifyMenuResource(query.menu());
        MenuSourcePolicy.validateProof(json, query.menu(), first, now());
        var authority = authorities.capture(query.connection());
        ChannelCatalogAuthorityPolicy.validate(query.connection(), authority, clock.instant());
        var second = menus.verifyMenuResource(query.menu());
        var observed = clock.instant();
        var observedAt = OffsetDateTime.ofInstant(observed, ZoneOffset.UTC);
        MenuSourcePolicy.validateProof(json, query.menu(), first, observedAt);
        MenuSourcePolicy.validateProof(json, query.menu(), second, observedAt);
        ChannelCatalogAuthorityPolicy.validate(query.connection(), authority, observed);
        if (!first.publication().equals(second.publication())
                || !first.resource().equals(second.resource())) {
            throw conflict();
        }
        var body = json.readValue(second.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var bindings = body.menu().channelBindings().stream()
                .filter(b -> query.bindingNo().equals(b.bindingNo())).toList();
        var contexts = body.menu().definition().contexts().stream()
                .filter(c -> query.bindingNo().equals(c.channelBindingNo())).toList();
        if (bindings.size() != 1 || contexts.size() != 1) throw conflict();
        var binding = bindings.getFirst();
        var context = contexts.getFirst();
        var connection = authority.connection();
        if (!query.menu().menuNo().equals(binding.menuNo())
                || !query.menu().storeNo().equals(binding.storeNo())
                || !"ACTIVE".equals(binding.status())
                || !authority.provider().providerCode().equals(binding.provider())
                || !authority.account().externalAccountRef().equals(binding.channelAccountRef())
                || !connection.externalStoreRef().equals(binding.externalStoreRef())
                || !"FOOD".equals(connection.sourceOwner())
                || !"LOCAL".equals(connection.catalogAuthority())
                || !"INSTANT".equals(connection.fulfillmentProfile())
                || !"CHANNEL".equals(context.orderSource())
                || !"DELIVERY".equals(context.fulfillment())) {
            throw conflict();
        }
        var until = earliest(started.plusSeconds(30), first.validUntil().toInstant());
        until = earliest(until, second.validUntil().toInstant());
        until = earliest(until, authority.validUntil());
        if (!until.isAfter(observed)) throw conflict();
        return new Facts(query, second, authority, binding, context, observed, until);
    }

    private static void validateQuery(Query query) {
        if (query == null || !MaterialCatalogFacts.validKey(query.bindingNo(), 32)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MenuSourcePolicy.validateQuery(query.menu());
        ChannelCatalogAuthorityPolicy.validateQuery(query.connection());
        var menu = query.menu();
        if (menu.kind() != Kind.MENU || menu.mode() != Mode.CURRENT
                || !menu.menuNo().equals(menu.resourceRef())
                || !menu.entityNo().equals(query.connection().entityNo())
                || !menu.storeNo().equals(query.connection().storeNo())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
