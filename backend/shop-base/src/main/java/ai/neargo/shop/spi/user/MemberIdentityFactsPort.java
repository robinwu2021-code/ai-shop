package ai.neargo.shop.spi.user;
import java.util.*;
/** User-owned canonical identity evidence; no phone ciphertext or arbitrary user reassignment. */
public interface MemberIdentityFactsPort {
 record Identity(String customerNo,String userNo,String phoneMasked,boolean consistent){}
 Optional<Identity> find(String tenant,String customer);
 Identity resolvePhone(String tenant,String phone);
 List<Identity> match(String tenant,List<String> allowedCustomers,String keyword);
}
