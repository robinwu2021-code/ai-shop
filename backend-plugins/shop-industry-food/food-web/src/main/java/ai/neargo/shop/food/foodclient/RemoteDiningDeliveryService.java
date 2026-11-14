package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;
import ai.neargo.shop.food.service.DiningDeliveryService;
import ai.neargo.shop.food.spi.DiningDeliveryInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Keeps the RemoteDiningDeliveryService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public class RemoteDiningDeliveryService implements DiningDeliveryService {
    private final DiningDeliveryInternalApi api;

    public RemoteDiningDeliveryService(DiningDeliveryInternalApi api) {
        this.api = api;
    }

    @Override
    public DeliverySnapshot saveStaff(String e, String s, String a, String c, SaveReq r, String k) {
        return saved(
                r,
                valid(
                        e,
                        s,
                        c,
                        call(
                                () ->
                                        api.saveStaff(
                                                new DiningDeliveryInternalApi.StaffSave(
                                                        e, s, a, c, r, k)))));
    }

    @Override
    public DeliverySnapshot saveCustomer(String c, CartAccess a, SaveReq r, String k) {
        return saved(
                r,
                valid(
                        null,
                        null,
                        c,
                        call(
                                () ->
                                        api.saveCustomer(
                                                new DiningDeliveryInternalApi.CustomerSave(
                                                        c, a, r, k)))));
    }

    @Override
    public DeliverySnapshot getStaff(String e, String s, String c) {
        return valid(
                e, s, c, call(() -> api.getStaff(new DiningDeliveryInternalApi.Source(e, s, c))));
    }

    @Override
    public DeliverySnapshot getCustomer(String c, CartAccess a) {
        return valid(
                null,
                null,
                c,
                call(() -> api.getCustomer(new DiningDeliveryInternalApi.CustomerQuery(c, a))));
    }

    @Override
    public DeliverySnapshot get(String e, String s, String c) {
        return valid(e, s, c, call(() -> api.get(new DiningDeliveryInternalApi.Source(e, s, c))));
    }

    private DeliverySnapshot saved(SaveReq r, DeliverySnapshot result) {
        if (result.cartVersion() != r.cartVersion() + 1
                || !r.receiverName().equals(result.receiverName())
                || !r.receiverPhone().equals(result.receiverPhone())
                || !r.address().equals(result.address())
                || !Objects.equals(r.deliveryInstruction(), result.deliveryInstruction())
                || !Objects.equals(r.latE6(), result.latE6())
                || !Objects.equals(r.lngE6(), result.lngE6())) {
            bad();
        }
        return result;
    }

    private DeliverySnapshot valid(String e, String s, String c, DeliverySnapshot result) {
        if (result == null
                || result.entityNo() == null
                || result.storeNo() == null
                || !c.equals(result.cartNo())
                || e != null && !e.equals(result.entityNo())
                || s != null && !s.equals(result.storeNo())
                || result.cartVersion() < 0
                || result.revision() < 0
                || result.receiverName() == null
                || result.receiverPhone() == null
                || result.address() == null) {
            bad();
        }
        return result;
    }

    private <T> T call(Supplier<T> action) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, action);
        } catch (ServiceCallException f) {
            ErrorCode c =
                    switch (f.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (c != null) {
                throw BizException.of(c);
            }
            throw f;
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
