package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.ReservationPreorderContracts.ConvertReq;
import ai.neargo.shop.food.dto.ReservationPreorderContracts.PreorderVO;
import ai.neargo.shop.food.dto.ReservationPreorderContracts.SaveReq;

public interface ReservationPreorderService {
    PreorderVO save(
            String entity,
            String store,
            String actor,
            String reservation,
            SaveReq request,
            String key);

    PreorderVO get(String entity, String store, String reservation, String preorder);

    CartResultVO prepareCart(
            String entity,
            String store,
            String actor,
            String reservation,
            String preorder,
            long version,
            String key);

    DiningOrderBatchVO convert(
            String entity,
            String store,
            String actor,
            String reservation,
            String preorder,
            ConvertReq request,
            String key);

    PreorderVO cancel(
            String entity,
            String store,
            String actor,
            String reservation,
            String preorder,
            long version,
            String reason,
            String key);
}
