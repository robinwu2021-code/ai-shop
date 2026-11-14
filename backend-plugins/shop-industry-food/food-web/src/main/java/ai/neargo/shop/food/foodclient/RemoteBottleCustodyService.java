package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Custody;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Movement;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Result;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;
import ai.neargo.shop.food.service.BottleCustodyService;
import ai.neargo.shop.food.spi.BottleCustodyInternalApi;
import ai.neargo.shop.food.spi.BottleCustodyInternalApi.Query;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/**
 * Typed transport preserves original idempotency keys and scope; it cannot write a shadow custody
 * balance.
 */
public final class RemoteBottleCustodyService implements BottleCustodyService {
    private final BottleCustodyInternalApi api;

    public RemoteBottleCustodyService(BottleCustodyInternalApi api) {
        this.api = api;
    }

    @Override
    public Result deposit(String entity, String store, String actor, Deposit request, String key) {
        var result =
                invoke(
                        () ->
                                api.deposit(
                                        new BottleCustodyInternalApi.DepositCommand(
                                                entity, store, actor, request, key)));
        receipt(result, entity, store, null);
        if (!request.customerNo().equals(result.custody().customerNo())
                || !request.skuNo().equals(result.custody().skuNo())) throw wrong();
        return result;
    }

    @Override
    public Result withdraw(
            String entity,
            String store,
            String actor,
            String custody,
            Withdraw request,
            String key) {
        var result =
                invoke(
                        () ->
                                api.withdraw(
                                        new BottleCustodyInternalApi.WithdrawCommand(
                                                entity, store, actor, custody, request, key)));
        receipt(result, entity, store, custody);
        return result;
    }

    @Override
    public Custody get(String entity, String store, String number) {
        var result = invoke(() -> api.get(new Query(entity, store, number, null, 1, 20)));
        custody(result, entity, store, number);
        return result;
    }

    @Override
    public PageData<Custody> list(
            String entity, String store, String customer, int page, int size) {
        var result = invoke(() -> api.list(new Query(entity, store, null, customer, page, size)));
        result.records()
                .forEach(
                        row -> {
                            custody(row, entity, store, null);
                            if (!customer.equals(row.customerNo())) throw wrong();
                        });
        return result;
    }

    @Override
    public PageData<Movement> movements(
            String entity, String store, String number, int page, int size) {
        var result =
                invoke(() -> api.movements(new Query(entity, store, number, null, page, size)));
        if (result.records().stream().anyMatch(row -> !number.equals(row.custodyNo())))
            throw wrong();
        return result;
    }

    private static void custody(Custody row, String entity, String store, String number) {
        if (row == null
                || !entity.equals(row.entityNo())
                || !store.equals(row.storeNo())
                || number != null && !number.equals(row.custodyNo())) throw wrong();
    }

    private static void receipt(Result result, String entity, String store, String number) {
        custody(result.custody(), entity, store, number);
        if (result.movement() == null
                || !result.custody().custodyNo().equals(result.movement().custodyNo()))
            throw wrong();
    }

    private static <T> T invoke(Supplier<T> action) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, action);
            if (result == null) throw wrong();
            return result;
        } catch (ServiceCallException failure) {
            throw switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }

    private static IllegalStateException wrong() {
        return new IllegalStateException("Wrong original custody receipt");
    }
}
