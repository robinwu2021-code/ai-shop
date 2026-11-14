package ai.neargo.shop.merchant.service;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import java.util.Map;
public interface StoreSettingsService {
    record View(StoreProfileVO profile, Map<String,String> sectionVersions) {}
    View load(String entityNo,String storeNo);
    View basic(String entityNo,String storeNo,String expectedVersion,String name,String address,String detail);
    View hours(String entityNo,String storeNo,String expectedVersion,String hours);
}
