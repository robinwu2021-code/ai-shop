package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.product.review.ReviewService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 平台端 · 评价治理与差评裁决（P-13.1）。
 *
 * <p><b>它是 B 端差评申诉的另一半。</b> 商家侧的申诉（B-9.4）已经能提交，
 * 但平台一直没有裁决入口 —— 申诉提交后永远停在 PENDING，
 * 而商家看到的是「已提交，等待处理」，等多久都不会有结果。
 * 这与「类目挂了门槛却没有发证机关」是同一个形状的缺口。
 */
@Profile("ops")
@RestController
@Validated
public class OpsReviewController {

    /** 评分参数的键与默认值。默认值写在这里而不是只写在迁移里：新环境没灌种子也能开页面 */
    private static final String SCORE_KEY = "review.score-config";
    private static final String SCORE_DEFAULT =
            "{\"weightProduct\":50,\"weightFulfill\":30,\"weightService\":20,"
                    + "\"newMerchantProtectDays\":30,\"decayHalfLifeDays\":180}";

    private final ReviewService reviewService;
    private final SettingPort settingPort;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public OpsReviewController(ReviewService reviewService, SettingPort settingPort,
                               AuditLogPort auditLogPort, ObjectMapper json) {
        this.reviewService = reviewService;
        this.settingPort = settingPort;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @GetMapping("/ops/reviews")
    @PreAuthorize("@perm.can('" + Perms.REVIEW_GOVERN + "')")
    public List<ReviewService.OpsReviewVO> reviews(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String merchantNo,
                                                   @RequestParam(required = false) String keyword) {
        return reviewService.opsList(status, merchantNo, keyword);
    }

    /** 评价审核。驳回必须写理由。 */
    @PostMapping("/ops/reviews/{reviewNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.REVIEW_GOVERN + "')")
    public ReviewService.OpsReviewVO decide(@PathVariable String reviewNo, @RequestBody DecideReq req) {
        var vo = reviewService.decide(reviewNo, Boolean.TRUE.equals(req.pass()), req.reason(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("REVIEW_DECIDE", reviewNo,
                Boolean.TRUE.equals(req.pass()) ? "通过" : "驳回：" + req.reason());
        return vo;
    }

    @GetMapping("/ops/review-appeals")
    @PreAuthorize("@perm.can('" + Perms.REVIEW_GOVERN + "')")
    public List<ReviewService.OpsAppealVO> appeals(@RequestParam(required = false) String status) {
        return reviewService.appeals(status);
    }

    /**
     * 裁决差评申诉。{@code uphold=true} 支持商家（差评从 C 端消失），
     * {@code false} 驳回申诉（差评保留）。<b>两种都必须写裁决说明</b> —— 商家会看到。
     */
    @PostMapping("/ops/review-appeals/{appealNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.REVIEW_GOVERN + "')")
    public ReviewService.OpsAppealVO decideAppeal(@PathVariable String appealNo,
                                                  @RequestBody AppealDecideReq req) {
        boolean uphold = Boolean.TRUE.equals(req.uphold());
        var vo = reviewService.decideAppeal(appealNo, uphold, req.verdict(),
                SecurityUtils.currentUserNo());
        // 裁决直接影响一家店的公开评分，必须能追到是谁在什么时候裁的
        auditLogPort.record("REVIEW_APPEAL_DECIDE", appealNo,
                (uphold ? "支持商家（差评下架）：" : "驳回申诉（差评保留）：") + req.verdict());
        return vo;
    }

    // ---------------------------------------------------------------- 评分参数

    @GetMapping("/ops/review-score-config")
    @PreAuthorize("@perm.can('" + Perms.REVIEW_GOVERN + "')")
    public ScoreConfig scoreConfig() {
        return json.readValue(settingPort.get(SCORE_KEY, SCORE_DEFAULT), ScoreConfig.class);
    }

    /**
     * 保存评分参数。
     *
     * <p><b>三维权重之和必须为 100</b>：不是 100 的话每家店的总分都会被整体拉高或压低，
     * 而这个偏移是全平台一致的，从任何一家店的分数上都看不出异常。
     */
    @PostMapping("/ops/review-score-config")
    @PreAuthorize("@perm.can('" + Perms.REVIEW_GOVERN + "')")
    public ScoreConfig saveScoreConfig(@RequestBody ScoreConfig req) {
        int sum = req.weightProduct() + req.weightFulfill() + req.weightService();
        if (sum != 100 || req.newMerchantProtectDays() < 0 || req.decayHalfLifeDays() <= 0) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.BAD_REQUEST);
        }
        settingPort.put(SCORE_KEY, json.writeValueAsString(req), SecurityUtils.currentUserNo());
        // 改参数会改变**历史评价**的呈现（时效衰减是实时算的），所以这条审计不是可选项
        auditLogPort.record("REVIEW_SCORE_CONFIG", SCORE_KEY,
                "权重 %d/%d/%d，保护期 %d 天，半衰期 %d 天".formatted(
                        req.weightProduct(), req.weightFulfill(), req.weightService(),
                        req.newMerchantProtectDays(), req.decayHalfLifeDays()));
        return req;
    }

    public record DecideReq(Boolean pass, String reason) {
    }

    public record AppealDecideReq(Boolean uphold, String verdict) {
    }

    /**
     * @param newMerchantProtectDays 新商家保护期：期内不展示低于阈值的均分，避免首单差评直接判死
     * @param decayHalfLifeDays      时效衰减半衰期：越久远的评价权重越低
     */
    public record ScoreConfig(int weightProduct, int weightFulfill, int weightService,
                              int newMerchantProtectDays, int decayHalfLifeDays) {
    }
}
