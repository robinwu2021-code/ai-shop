package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.ReplayVO;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.spi.FoodKdsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps the RemoteFoodKdsService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public class RemoteFoodKdsService implements FoodKdsService {
    private final FoodKdsInternalApi api;

    public RemoteFoodKdsService(FoodKdsInternalApi api) {
        this.api = api;
    }

    @Override
    public SubscriptionVO grant(String e, String s, String a, GrantReq r, String k) {
        var result =
                valid(e, s, call(() -> api.grant(new FoodKdsInternalApi.Grant(e, s, a, r, k))));
        if (!r.deviceNo().equals(result.deviceNo())
                || !r.deviceActorNo().equals(result.actorNo())
                || !result.stationNos()
                        .equals(r.stationNos().stream().distinct().sorted().toList())) {
            bad();
        }
        return result;
    }

    @Override
    public SubscriptionVO revoke(String e, String s, String a, String no, RevokeReq r, String k) {
        var result =
                valid(
                        e,
                        s,
                        call(() -> api.revoke(new FoodKdsInternalApi.Revoke(e, s, a, no, r, k))));
        if (!no.equals(result.subscriptionNo()) || !"REVOKED".equals(result.status())) {
            bad();
        }
        return result;
    }

    @Override
    public ReplayVO replay(String e, String s, String a, String no, long after, int limit) {
        var result =
                call(() -> api.replay(new FoodKdsInternalApi.Replay(e, s, a, no, after, limit)));
        if (result == null
                || !e.equals(result.entityNo())
                || !s.equals(result.storeNo())
                || !no.equals(result.subscriptionNo())
                || result.events() == null
                || result.events().size() > limit
                || result.nextCursor() < after
                || result.nextCursor() > result.watermark()
                || result.hasMore() != (result.nextCursor() < result.watermark())) {
            bad();
        }
        long cursor = after;
        for (var event : result.events()) {
            if (event == null
                    || event.cursor() <= cursor
                    || event.cursor() > result.nextCursor()
                    || event.eventNo() == null
                    || event.payload() == null) {
                bad();
            }
            cursor = event.cursor();
        }
        return result;
    }

    @Override
    public void append(EventDraft e) {
        if (!Boolean.TRUE.equals(call(() -> api.append(e)))) {
            bad();
        }
    }

    private SubscriptionVO valid(String e, String s, SubscriptionVO result) {
        if (result == null
                || !e.equals(result.entityNo())
                || !s.equals(result.storeNo())
                || result.subscriptionNo() == null
                || result.version() < 0
                || result.expiresAt() == null
                || !Set.of("ACTIVE", "REVOKED").contains(result.status())) {
            bad();
        }
        return result;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, request);
        } catch (ServiceCallException failure) {
            ErrorCode code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) {
                throw BizException.of(code);
            }
            throw failure;
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
