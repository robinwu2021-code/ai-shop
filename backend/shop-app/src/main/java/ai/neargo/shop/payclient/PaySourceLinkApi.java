package ai.neargo.shop.payclient;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 独立支付服务（near-pay）的「来源申请支付服务」：{@code POST /internal/pay/v2/source-links/apply}。
 * 建平台商户 + 来源链接（VERIFY_PENDING）+ 门店关联 + 资料修订。调用方由 {@code X-Internal-Caller} 解析成来源系统，
 * 请求体不带 sourceSystem；调用方头由 PAY 的按服务拦截器 {@link PayCallerHeaders} 统一加上。
 */
public interface PaySourceLinkApi {

    String PATH = "/internal/pay/v2/source-links/apply";

    record StoreInput(String externalId, Long revision, String profileHash, String storeNo) {
    }

    record ActorRef(String realm, String subject, String authVersion, String authorizationRef) {
    }

    record Profile(String purpose, String legalName, String registrationNo, String registeredAddress,
                   String representativeName, String contactPhone, String industry, List<Map<String, String>> materials) {
    }

    record Apply(String sourceEventId, String sourceScope, String sourceEntityRef, Long entityRevision, String entityName,
                 List<StoreInput> stores, ActorRef actorRef, String capabilityType, Long profileRevision, Profile profile) {
    }

    /** near-pay SourceLinksResult，原样转给运营端 */
    record Result(String merchantRef, List<Object> storeLinks, String applicationRef, String status) {
    }

    @PostExchange(PATH)
    Result apply(@RequestBody Apply body);

    /**
     * 申请审核状态：NONE / PENDING / NEED_MORE / REJECTED / APPROVED；reviewReason 只在需补件 / 被拒时有。
     */
    record Status(String status, String merchantRef, String linkStatus, String profileStatus, String reviewReason) {
    }

    @org.springframework.web.service.annotation.GetExchange("/internal/pay/v2/source-links/status")
    Status status(@org.springframework.web.bind.annotation.RequestParam("sourceScope") String sourceScope,
                  @org.springframework.web.bind.annotation.RequestParam("sourceEntityRef") String sourceEntityRef);
    record StoreClaimCommand(String requestNo, String requestHash, String sourceScope,
            String sourceEntityRef, String targetEntityRef, String storeRef, String storeNo, long expectedRevision, boolean retireSource) {
        public StoreClaimCommand(String requestNo, String requestHash, String sourceScope,
                String sourceEntityRef, String targetEntityRef, String storeRef, String storeNo, long expectedRevision) {
            this(requestNo, requestHash, sourceScope, sourceEntityRef, targetEntityRef, storeRef, storeNo, expectedRevision, false);
        }
    }
    record StoreClaimView(String requestNo, String state, String claimRef, String sourceEntityRef,
            String targetEntityRef, String storeRef, String storeNo) {}
    record StoreRelinkCheck(boolean complete, boolean sourceApplied, boolean associationEver,
            boolean paymentHistory, boolean storedValueHistory, List<String> blockers) {}

    @PostExchange("/internal/pay/v2/source-links/store-claims/prepare")
    StoreClaimView prepareStoreClaim(@RequestBody StoreClaimCommand body);
    @org.springframework.web.service.annotation.GetExchange("/internal/pay/v2/source-links/store-claims/{requestNo}")
    StoreClaimView storeClaim(@org.springframework.web.bind.annotation.PathVariable("requestNo") String requestNo,
            @org.springframework.web.bind.annotation.RequestParam("sourceScope") String sourceScope);
    @PostExchange("/internal/pay/v2/source-links/store-claims/{requestNo}/commit")
    StoreClaimView commitStoreClaim(@org.springframework.web.bind.annotation.PathVariable("requestNo") String requestNo,
            @RequestBody StoreClaimCommand body);
    @PostExchange("/internal/pay/v2/source-links/store-claims/{requestNo}/abort")
    StoreClaimView abortStoreClaim(@org.springframework.web.bind.annotation.PathVariable("requestNo") String requestNo,
            @RequestBody StoreClaimCommand body);
    @org.springframework.web.service.annotation.GetExchange("/internal/pay/v2/source-links/relink-check")
    StoreRelinkCheck relinkCheck(@org.springframework.web.bind.annotation.RequestParam("sourceScope") String sourceScope,
            @org.springframework.web.bind.annotation.RequestParam("sourceEntityRef") String entity,
            @org.springframework.web.bind.annotation.RequestParam("storeRef") String storeRef,
            @org.springframework.web.bind.annotation.RequestParam("storeNo") String storeNo);

}
