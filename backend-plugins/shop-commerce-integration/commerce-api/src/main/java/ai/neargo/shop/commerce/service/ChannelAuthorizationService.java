package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.AuthorizationActionVO;
import ai.neargo.shop.commerce.dto.AuthorizationCallbackResult;
import ai.neargo.shop.commerce.dto.AuthorizationRevisionVO;
import ai.neargo.shop.commerce.dto.AuthorizeChannelAccountReq;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.RevokeChannelAuthorizationReq;
import ai.neargo.shop.common.PageData;

/** 公开账号操作与state证明的回调共用同一原动作，不以本地状态伪造外部授权。 */
public interface ChannelAuthorizationService {
    AuthorizationActionVO authorize(
            String entity,
            String accountNo,
            String actor,
            AuthorizeChannelAccountReq req,
            String key);

    OperationVO revoke(
            String entity,
            String accountNo,
            String actor,
            RevokeChannelAuthorizationReq req,
            String key);

    PageData<AuthorizationRevisionVO> listRevisions(
            String entity, String accountNo, int page, int size);

    AuthorizationCallbackResult completeAuthorization(
            String providerCode, String state, String code, String error);
}
