package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Custody;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Movement;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Result;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;

/** Same-store physical property ledger is independent from goods stock and checkout balances. */
public interface BottleCustodyService {
    Result deposit(String entity, String store, String actor, Deposit request, String key);

    Result withdraw(
            String entity,
            String store,
            String actor,
            String custodyNo,
            Withdraw request,
            String key);

    Custody get(String entity, String store, String custodyNo);

    PageData<Custody> list(String entity, String store, String customerNo, int page, int size);

    PageData<Movement> movements(String entity, String store, String custodyNo, int page, int size);
}
