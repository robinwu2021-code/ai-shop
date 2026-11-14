package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.MenuWithdrawalSourceContracts;

/** No missing/deleted/unknown menu can be treated as an intentional withdrawal. */
public interface MenuWithdrawalSourceService {
    MenuWithdrawalSourceContracts.Disposition disposition(
            MenuWithdrawalSourceContracts.Query query);

    MenuWithdrawalSourceContracts.Proof capture(MenuWithdrawalSourceContracts.Query query);
}
