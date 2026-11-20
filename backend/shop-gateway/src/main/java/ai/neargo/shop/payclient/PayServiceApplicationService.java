package ai.neargo.shop.payclient;

/** Actual authorized source submission: freeze an original locally, then send it outside local locks. */
public interface PayServiceApplicationService {
    PaySourceLinkApi.Result apply(String entityNo,PayServiceApplyRequests.ApplyReq form,String realm,String actorNo);

    /** UME 迁移建档（TDD-UME迁移建档）：不带资料，只限 UME 导入主体；支付侧按固定事件号幂等 */
    PaySourceLinkApi.Result migrate(String entityNo,String actorNo);
}
