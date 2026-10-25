package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.DecisionReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.HandoverReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.HandoverVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;

import java.util.List;

public interface BusinessOperationsService {
    Evidence evidence(String entity, String store, Source source);

    HandoverVO propose(String entity, String store, String actor, HandoverReq request, String key);

    HandoverVO handover(String entity, String store, String handover);

    HandoverVO accept(
            String entity,
            String store,
            String actor,
            String handover,
            DecisionReq request,
            String key);

    HandoverVO cancel(
            String entity,
            String store,
            String actor,
            String handover,
            DecisionReq request,
            String key);

    CaseVO openCase(String entity, String store, String actor, CaseReq request, String key);

    List<CaseVO> cases(String entity, String store, String status);

    CaseVO recoverCase(
            String entity,
            String store,
            String actor,
            String caseNo,
            DecisionReq request,
            String key);

    CaseVO resolveCase(
            String entity,
            String store,
            String actor,
            String caseNo,
            DecisionReq request,
            String key);
}
