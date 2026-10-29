package ai.neargo.shop.food.service;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
public interface FoodStockBalanceService {
    BalanceVO initialize(String entity, String store, String actor, Initialize input, String key);
    BalanceVO changePolicy(String entity, String store, String item, String actor, Policy input, String key);
    BalanceVO adjust(String entity, String store, String item, String actor, Adjust input, String key);
    BalanceVO getBalance(String entity, String store, String item);
    PageData<BalanceVO> listBalances(String entity, String store, int page, int size);
    PageData<MovementVO> listMovements(String entity, String store, int page, int size, String item, String action,
                                      java.time.LocalDateTime from, java.time.LocalDateTime to);
}
