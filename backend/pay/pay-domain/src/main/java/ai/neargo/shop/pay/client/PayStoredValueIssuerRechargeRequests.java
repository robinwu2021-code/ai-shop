package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges;
public final class PayStoredValueIssuerRechargeRequests {
 private PayStoredValueIssuerRechargeRequests(){}
 public record Options(Query query,String cursor,int size){public Options{ContractChecks.present(query);ContractChecks.require(size>0&&size<=50);}}
 public record Confirm(EntitlementContext context,String entityNo,StoredValueIssuerRecharges.Create command){public Confirm{ContractChecks.present(context);ContractChecks.id(entityNo);ContractChecks.present(command);}}
 public record Replay(EntitlementContext context,String entityNo,StoredValueIssuerRecharges.Replay command){public Replay{ContractChecks.present(context);ContractChecks.id(entityNo);ContractChecks.present(command);}}
}
