package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CheckCollaborationContracts.FreezeReq;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.FreezeVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.LineMoveReq;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.LineMoveVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.MoveReq;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.MoveVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.ReceivablePreview;
import ai.neargo.shop.food.dto.OperationVO;

public interface CheckCollaborationService {
    ReceivablePreview previewReceivable(String entity, String store, String check);

    String sourceHash(String entity, String store, String check);

    OperationVO releaseService(
            String entity,
            String store,
            String actor,
            String check,
            long version,
            String reason,
            String key);

    String currentLineOwner(String entity, String store, String foodLineNo);

    LineMoveVO moveLines(
            String entity, String store, String actor, LineMoveReq request, String key);

    MoveVO moveBatches(String entity, String store, String actor, MoveReq request, String key);

    FreezeVO freeze(
            String entity, String store, String actor, String check, FreezeReq request, String key);

    FreezeVO release(
            String entity,
            String store,
            String actor,
            String freeze,
            long version,
            String reason,
            String key);
}
