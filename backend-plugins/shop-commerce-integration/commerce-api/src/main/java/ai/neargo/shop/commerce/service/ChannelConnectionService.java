package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelConnectionVO;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.common.PageData;

/** 首版连接一店一行业，不能通过配置换主体或抢占同一远端店。 */
public interface ChannelConnectionService {
    ChannelConnectionVO createConnection(
            String entity, String store, String actor, CreateChannelConnectionReq req, String key);

    ChannelConnectionVO getConnection(String entity, String store, String connectionNo);

    PageData<ChannelConnectionVO> listConnections(String entity, String store, int page, int size);
}
