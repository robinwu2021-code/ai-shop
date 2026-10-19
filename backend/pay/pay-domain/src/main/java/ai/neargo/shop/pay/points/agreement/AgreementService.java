package ai.neargo.shop.pay.points.agreement;

import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;

/** Only the merchant's authoritative owner signs a versioned, published acceptance contract. */
public interface AgreementService {
    void publishTerms(String typeCode,String version,String text,String operatorNo);
    AgreementSnapshot sign(SignCommand command,String actor);
    void terminate(String typeCode,String storeNo,String actor);
    AgreementSnapshot active(String typeCode,String storeNo);
    record SignCommand(String typeCode,String merchantNo,String storeNo,String termsVersion,String termsHash,
                       String settleMode,FundsContext fundsContext) { }
}
