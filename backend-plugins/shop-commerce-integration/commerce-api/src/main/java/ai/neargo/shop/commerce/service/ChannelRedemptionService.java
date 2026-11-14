package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.RedemptionContracts;

public interface ChannelRedemptionService {
    RedemptionContracts.Previewed preview(
            String entity,
            String store,
            String actor,
            RedemptionContracts.Preview request,
            String key);

    OperationVO redeem(
            String entity,
            String store,
            String actor,
            RedemptionContracts.Redeem request,
            String key);

    RedemptionContracts.Redemption getRedemption(String entity, String store, String number);

    OperationVO reverse(
            String entity,
            String store,
            String actor,
            String number,
            RedemptionContracts.Reverse request,
            String key);
}
