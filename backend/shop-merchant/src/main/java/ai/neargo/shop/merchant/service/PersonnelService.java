package ai.neargo.shop.merchant.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.ChangePersonnelStatusReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelAccountLinkVO;
import ai.neargo.shop.merchant.dto.PersonnelAssignmentVO;
import ai.neargo.shop.merchant.dto.PersonnelChangeVO;
import ai.neargo.shop.merchant.dto.PersonnelQuery;
import ai.neargo.shop.merchant.dto.PersonnelVO;

/** Shared retail/food/beauty business personnel; login membership and resource arbitration remain separate. */
public interface PersonnelService {
    PageData<PersonnelVO> listPersonnel(String entityNo, String currentStoreNo, String actorNo, PersonnelQuery query);
    PersonnelVO createPersonnel(String entityNo, String currentStoreNo, String actorNo,
            CreatePersonnelReq request, String idempotencyKey);
    PersonnelAssignmentVO assignPersonnel(String entityNo, String currentStoreNo, String actorNo,
            String personnelNo, AssignPersonnelReq request, String idempotencyKey);
    PersonnelAccountLinkVO linkPersonnelAccount(String entityNo, String currentStoreNo, String actorNo,
            String personnelNo, LinkPersonnelAccountReq request, String idempotencyKey);
    PersonnelChangeVO changePersonnelStatus(String entityNo, String currentStoreNo, String actorNo,
            String personnelNo, ChangePersonnelStatusReq request, String idempotencyKey);
}
