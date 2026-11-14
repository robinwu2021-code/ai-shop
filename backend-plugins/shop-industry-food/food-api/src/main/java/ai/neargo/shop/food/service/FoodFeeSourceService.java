package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.FoodFeeSourceContracts.BindReq;
import ai.neargo.shop.food.dto.FoodFeeSourceContracts.BindingVO;

import java.util.List;

public interface FoodFeeSourceService {
    BindingVO bind(String entity, String store, String actor, BindReq request, String key);

    List<BindingVO> bindings(String entity, String store, String check);
}
