package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelAccountVO;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.common.PageData;

/** 可信调用方传入主体/操作者；账号与授权状态分开管理。 */
public interface ChannelAccountService {
    ChannelAccountVO createAccount(
            String entity, String actor, CreateChannelAccountReq req, String key);

    ChannelAccountVO getAccount(String entity, String accountNo);

    PageData<ChannelAccountVO> listAccounts(String entity, int page, int size);
}
