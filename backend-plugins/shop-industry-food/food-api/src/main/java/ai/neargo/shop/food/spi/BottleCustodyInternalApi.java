package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Custody;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Movement;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Result;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Original custody ledger is the same service in embedded and independent FOOD. */
public interface BottleCustodyInternalApi {
    record DepositCommand(String entity, String store, String actor, Deposit request, String key) {}

    record WithdrawCommand(
            String entity,
            String store,
            String actor,
            String custodyNo,
            Withdraw request,
            String key) {}

    record Query(
            String entity, String store, String custodyNo, String customerNo, int page, int size) {}

    @PostExchange("/internal/food/bottle-custody/deposit")
    Result deposit(@RequestBody DepositCommand command);

    @PostExchange("/internal/food/bottle-custody/withdraw")
    Result withdraw(@RequestBody WithdrawCommand command);

    @PostExchange("/internal/food/bottle-custody/get")
    Custody get(@RequestBody Query query);

    @PostExchange("/internal/food/bottle-custody/list")
    PageData<Custody> list(@RequestBody Query query);

    @PostExchange("/internal/food/bottle-custody/movements")
    PageData<Movement> movements(@RequestBody Query query);
}
