package ai.neargo.shop.merchant.service;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import java.util.Map;
public interface StoreSettingsService {
    record View(StoreProfileVO profile, Map<String,String> sectionVersions) {}
    View load(String entityNo,String storeNo);
    View basic(String entityNo,String storeNo,String expectedVersion,String name,String address,String detail);
    /** 联系资料独立比较版本，使基本资料及营业时间客户端不覆盖联系人。 */
    View contact(String entityNo,String storeNo,String expectedVersion,String contactName,String contactPhone);
    View hours(String entityNo,String storeNo,String expectedVersion,String hours);
}
