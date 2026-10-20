package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Component
public class PointsOrderTermsPortImpl implements PointsOrderTermsPort {
    private final PointsReadStore read;
    private final AgreementService agreements;
    private final PointsAdministrationService admin;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();

    public PointsOrderTermsPortImpl(
            PointsReadStore read,
            AgreementService agreements,
            PointsAdministrationService admin,
            @Value("${shop.points.enabled:false}") boolean enabled) {
        this.read = read;
        this.agreements = agreements;
        this.admin = admin;
        this.enabled = enabled;
    }

    @Value("${shop.points.default-participation-enabled:${shop.points.unified-earning-enabled:false}}")
    private boolean defaultParticipation;

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public Optional<Terms> resolve(
            String market,
            String user,
            String merchant,
            String store,
            String scene,
            String client) {
        if (!enabled) return Optional.empty();
        if (blank(market)
                || blank(user)
                || blank(merchant)
                || blank(store)
                || blank(scene)
                || blank(client))
            throw new IllegalArgumentException("POINTS_TERMS_CONTEXT_REQUIRED");
        var type = read.activeType(market).orElse(null);
        if (type == null || type.status() != PointType.Status.ACTIVE) return Optional.empty();
        boolean earn =
                read.enabled(
                        new PointsReadStore.Availability(
                                type.typeCode(),
                                user,
                                store,
                                merchant,
                                market,
                                scene,
                                client,
                                Flow.EARN));
        var agreement = agreements.active(type.typeCode(), store);
        if (agreement != null
                && (!merchant.equals(agreement.merchantNo()) || !store.equals(agreement.storeNo())))
            throw new IllegalArgumentException("POINTS_TERMS_STORE_MISMATCH");
        boolean redeem =
                (agreement != null || defaultParticipation)
                        && read.enabled(
                                new PointsReadStore.Availability(
                                        type.typeCode(),
                                        user,
                                        store,
                                        merchant,
                                        market,
                                        scene,
                                        client,
                                        Flow.REDEEM));
        var fallback = admin.fallback(type.typeCode());
        return Optional.of(
                new Terms(
                        type.typeCode(),
                        type.currency(),
                        type.rate().pointsUnit(),
                        type.rate().minorUnit(),
                        earn,
                        redeem,
                        new PointsEarnPort.EarnRule(fallback.mode(), fallback.value()),
                        fallback.revision(),
                        json.writeValueAsString(agreement)));
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
