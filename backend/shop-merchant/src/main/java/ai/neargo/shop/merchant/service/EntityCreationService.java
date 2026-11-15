package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.merchant.dto.EntityVO;

/** Personal account creation is independent of store context and organization operating entities. */
public interface EntityCreationService {
    record Result(String requestNo, EntityVO entity, boolean replayed, String profileInitialization, String profileRequestNo) {
        public Result(String requestNo,EntityVO entity,boolean replayed){this(requestNo,entity,replayed,"NOT_REQUESTED",null);}
    }

    /** The permanent request key preserves intent after a lost response. */
    Result create(LoginUser user, String requestNo, String name, String legalForm);

    Result create(LoginUser user, String requestNo, String name, String legalForm, String initialProfileHash);

    /** Reads the current entity only while the requesting account remains its active owner. */
    Result find(LoginUser user, String requestNo);
}
