package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.dto.FoodSupportContracts.ReadinessVO;

import java.util.List;

/** Platform support reads original restaurant state without impersonating merchant commands. */
public interface FoodSupportService {
    ReadinessVO readiness();

    List<CaseVO> cases(String entityNo, String storeNo, String status);

    Evidence evidence(String entityNo, String storeNo, Source source);
}
