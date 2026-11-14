package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;

/** Read-only offer for an actual priced service source. Core freezes this selection before C09 consent. */
public interface StoredValueUseQuotePort {
    Quote quote(EntitlementContext context, SourceRef source);
    default Quote quote(EntitlementContext context,SourceRef source,StoredValueWallets.Choice choice){throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    enum Reason { OK, NO_ACCOUNT, FROZEN, INSUFFICIENT_APPLICABLE_BALANCE }
    record Selection(SourceRef source, String sourceHash, String accountNo, long amountMinor, long assetRevision) {
        public Selection {
            ContractChecks.present(source); ContractChecks.hash(sourceHash); ContractChecks.optionalId(accountNo);
            ContractChecks.require(amountMinor > 0 && assetRevision >= 0);
        }
    }
    record Quote(String quoteRef, SourceRef sourceRef, String accountNo, String currency, long amountMinor,
                 long applicableMinor, boolean redeemable, Reason reason, long assetRevision, String snapshotHash,
                 Instant expiresAt, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) StoredValueWallets.Proof walletProof) {
        public Quote(String quoteRef,SourceRef sourceRef,String accountNo,String currency,long amountMinor,long applicableMinor,boolean redeemable,Reason reason,long assetRevision,String snapshotHash,Instant expiresAt){this(quoteRef,sourceRef,accountNo,currency,amountMinor,applicableMinor,redeemable,reason,assetRevision,snapshotHash,expiresAt,null);}
        public Quote {
            ContractChecks.id(quoteRef); ContractChecks.present(sourceRef); ContractChecks.optionalId(accountNo);
            ContractChecks.currency(currency); ContractChecks.present(reason); ContractChecks.hash(snapshotHash);
            ContractChecks.present(expiresAt);
            ContractChecks.require(amountMinor > 0 && applicableMinor >= 0 && applicableMinor <= amountMinor
                    && assetRevision >= 0 && redeemable == (reason == Reason.OK));
            ContractChecks.require(!redeemable || accountNo != null && amountMinor == applicableMinor);
            if(walletProof!=null)ContractChecks.require(java.util.Objects.equals(accountNo,walletProof.selected().accountNo())&&currency.equals(walletProof.selected().currency())&&assetRevision==walletProof.selected().assetRevision());
        }
    }
}
