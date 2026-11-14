package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.MessageService.MerchantFeedbackCommand;
import ai.neargo.shop.message.dto.MessageVOs.DiagnosticVO;
import ai.neargo.shop.message.dto.MessageVOs.FeedbackVO;
import ai.neargo.shop.message.entity.MsgTicket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户 App 的「回报问题」与「我的回报」（TDD-商户App问题回报与版本 §2 接口一、二）。
 *
 * <p><b>账号范围，不要求选门店</b>：「选不了店」本身就是最常要回报的问题之一，所以两条路由登记在
 * {@code CommerceRoutePolicy.ACCOUNT}，不进门店成员路由；判权只要求「是商户账号」
 * （{@code @perm.merchantAccount()}），店员一个 biz 码都没有也能报。{@code X-Store-No} 带了也不当作门店上下文。
 *
 * <p><b>放在 portal 而不是 message 域</b>：核对门店号要用 {@link MerchantEntityService}（shop-merchant），
 * 落库用 {@link MessageService}（shop-core）—— 两个域在这里组合，与 {@code BizEntityController} 同处。
 *
 * <p>正文严、附带资料宽：分类、描述、版本由下面的 Bean Validation 卡住（10400、不落库）；
 * 门店号核对不过只丢弃门店号、记 {@code store_dropped}，照常落库 —— 最常见的回报正是「权限刚被收回、门店进不去」。
 */
@Profile("api")
@RestController
@Validated
public class BizFeedbackController {

    private final MessageService messageService;
    private final MerchantEntityService entityService;

    public BizFeedbackController(MessageService messageService, MerchantEntityService entityService) {
        this.messageService = messageService;
        this.entityService = entityService;
    }

    /** 提交一条回报。回报号就是工单号（{@code TK…}），客服在工单页按同一个号搜。 */
    @PostMapping("/biz/feedback")
    @PreAuthorize("@perm.merchantAccount()")
    public FeedbackReceipt submit(@Valid @RequestBody SubmitFeedbackReq req) {
        String storeNo = req.storeNo() == null || req.storeNo().isBlank() ? null : req.storeNo().strip();
        boolean mine = storeNo != null && entityService.myStores(SecurityUtils.requireUser()).stream()
                .flatMap(group -> group.stores().stream())
                .anyMatch(store -> storeNo.equals(store.storeNo()));
        FeedbackVO vo = messageService.createMerchantFeedback(new MerchantFeedbackCommand(
                req.category(), req.description(), req.errorCode(), req.page(),
                mine ? storeNo : null, storeNo != null && !mine,
                req.appVersion(), req.platform(), req.diagnostics()));
        return new FeedbackReceipt(vo.feedbackNo());
    }

    /** 我的回报：本账号的商户回报，最近 50 条，新的在前，带客服回复。 */
    @GetMapping("/biz/feedback/mine")
    @PreAuthorize("@perm.merchantAccount()")
    public List<FeedbackVO> mine() {
        return messageService.myMerchantFeedback();
    }

    /**
     * 回报请求体。描述在校验前去掉首尾空白：服务端下限是 1 字（App 输入框的「至少 4 字、最多 500 字」是端上更严的约束），
     * 上限 1000 字 —— 存储列是 VARCHAR(1024)，超出直接 10400，不截断（截断会让客服以为半句话是原文）。
     *
     * @param errorCode   ≤64，超长截断（App 的 {@code AppFailure.code}，已知最长 33 字）
     * @param page        ≤128，超长截断
     * @param storeNo     核对通过才存
     * @param appVersion  必填；≤32，超长截断
     * @param platform    android / ios 不分大小写；其它值（桌面上的 macOS 等）与缺失都归 OTHER，不拒单
     * @param diagnostics 最多留最后 300 条，每条只留 at / method / path / outcome
     */
    public record SubmitFeedbackReq(
            @NotNull @Pattern(regexp = MsgTicket.CATEGORY_PATTERN) String category,
            @NotBlank @Size(max = 1000) String description,
            String errorCode,
            String page,
            String storeNo,
            @NotBlank String appVersion,
            String platform,
            List<DiagnosticVO> diagnostics) {

        public SubmitFeedbackReq {
            description = description == null ? null : description.strip();
        }
    }

    /** @param feedbackNo 回报号，即工单号 */
    public record FeedbackReceipt(String feedbackNo) {
    }
}
