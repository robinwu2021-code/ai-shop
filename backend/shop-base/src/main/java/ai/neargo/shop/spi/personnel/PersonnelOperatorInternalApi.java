package ai.neargo.shop.spi.personnel;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** The bound route cannot select arbitrary accounts or a caller-supplied observation time. */
public interface PersonnelOperatorInternalApi {
    String PATH = "/internal/merchant/personnel-operator/current";
    String ENDPOINT = "PERSONNEL_OPERATOR_CURRENT";

    @PostExchange(PATH)
    InternalReply<PersonnelOperatorFactsPort.Observation> current(
            @RequestBody InternalCall<PersonnelOperatorFactsPort.Query> request);
}
