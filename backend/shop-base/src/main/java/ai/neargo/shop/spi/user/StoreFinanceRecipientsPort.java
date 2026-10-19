package ai.neargo.shop.spi.user;
import java.util.List;
/** Current store FINANCE permission, including custom roles and owner. */
public interface StoreFinanceRecipientsPort { List<String> recipients(String merchantNo,String storeNo); }
