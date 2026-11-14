package ai.neargo.shop.spi.user;

import java.util.List;
import java.util.Map;

/** Trusted merchant BFF only: search candidates are intersected with Pay store-account ownership. */
public interface StoredValueCustomerProfilePort {
    List<String> search(String tenantNo,String entityNo,String storeNo,String keyword,List<String> userNos);
    Map<String,Profile> profiles(String tenantNo,String entityNo,String storeNo,List<String> userNos);
    record Profile(String maskedName,String maskedPhone) { }
}
