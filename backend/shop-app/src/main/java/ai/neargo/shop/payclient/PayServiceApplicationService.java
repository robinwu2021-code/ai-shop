package ai.neargo.shop.payclient;

/** Actual authorized source submission: freeze an original locally, then send it outside local locks. */
public interface PayServiceApplicationService {
    PaySourceLinkApi.Result apply(String entityNo,PayServiceApplyRequests.ApplyReq form,String realm,String actorNo);
}
