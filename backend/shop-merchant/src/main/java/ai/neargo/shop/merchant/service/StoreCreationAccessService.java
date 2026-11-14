package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.LoginUser;
import java.util.List;

/** Account-owned creation choices; the existing write transaction remains authoritative. */
public interface StoreCreationAccessService {
    record Source(String entityNo,String name,String storeNo,String market,int storeQuota,int storeUsed,boolean canCreate) {}
    record Access(boolean canQuickStart,boolean canResumeQuickStart,List<Source> sources,boolean entityNameSupported, boolean storeOpeningSupported) {
        public Access(boolean canQuickStart, boolean canResumeQuickStart, List<Source> sources) {
            this(canQuickStart, canResumeQuickStart, sources, true, true);
        }
    }
    Access access(LoginUser user);
}
