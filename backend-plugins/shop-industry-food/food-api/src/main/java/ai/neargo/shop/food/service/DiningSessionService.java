package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableTokenVO;

/** Table-scoped credentials never confer platform login or ownership of an existing check. */
public interface DiningSessionService {
    TableTokenVO rotateTableToken(String entity, String store, String table, String actor,
            RotateTableTokenReq req, String key);
    OperationResultVO revokeTableToken(String entity, String store, String table, String actor,
            RevokeTableTokenReq req, String key);
    DiningSessionTableVO resolveTableToken(String tableToken);
    DiningSessionVO startSession(String entity, String store, String buyer, StartDiningSessionReq req, String key);
    OperationResultVO endSession(String session, String accessToken, String buyer, String key);
}
