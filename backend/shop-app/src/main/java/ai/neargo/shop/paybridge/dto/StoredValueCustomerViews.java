package ai.neargo.shop.paybridge.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;

public final class StoredValueCustomerViews {
    private StoredValueCustomerViews() { }
    public record Account(String accountRef,String currency,long availableMinor) { }
    public record Candidate(String memberNo,String displayName,String phoneMasked,String memberStatus,
                            boolean canRecharge,String unavailableReason,Account account) { }
    public record Selection(String customerRef,Instant expiresAt,String memberNo,String displayName,
                            String phoneMasked,Account account,String pendingScope) {
        public Selection(String ref,Instant expires,String member,String name,String phone,Account account){this(ref,expires,member,name,phone,account,null);}
        @Override public String toString(){return "Selection[customerRef=REDACTED]";}
    }
    public record Source(String type,String memberNo,String accountRef) {
        public Source {
            ContractChecks.require(("MEMBER".equals(type)&&memberNo!=null&&accountRef==null)
                    ||("ACCOUNT".equals(type)&&accountRef!=null&&memberNo==null));
            if("MEMBER".equals(type))ContractChecks.id(memberNo);
            else ContractChecks.require(accountRef!=null&&!accountRef.isBlank()&&accountRef.length()<=512);
        }
        @Override public String toString(){return "Source[type="+type+", reference=REDACTED]";}
    }
    public record SelectRequest(String storeNo,Source source) {
        public SelectRequest {ContractChecks.id(storeNo);ContractChecks.present(source);}
    }
}
