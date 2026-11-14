package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelConnectionVO;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.Configuration;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.SetStatus;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.Verify;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.common.PageData;

/** 首版连接一店一行业，不能通过配置换主体或抢占同一远端店。 */
public interface ChannelConnectionService {
    /**
     * Old clients cannot activate a newly introduced configuration or state capability by default.
     */
    default ChannelConnectionVO saveConfiguration(
            String entity,
            String store,
            String number,
            String actor,
            Configuration request,
            String key) {
        throw new UnsupportedOperationException("Connection writes unavailable");
    }

    default OperationVO verify(
            String entity, String store, String number, String actor, Verify request, String key) {
        throw new UnsupportedOperationException("Connection verification unavailable");
    }

    default ChannelConnectionVO setStatus(
            String entity,
            String store,
            String number,
            String actor,
            SetStatus request,
            String key) {
        throw new UnsupportedOperationException("Connection state unavailable");
    }

    ChannelConnectionVO createConnection(
            String entity, String store, String actor, CreateChannelConnectionReq req, String key);

    ChannelConnectionVO getConnection(String entity, String store, String connectionNo);

    PageData<ChannelConnectionVO> listConnections(String entity, String store, int page, int size);
}
