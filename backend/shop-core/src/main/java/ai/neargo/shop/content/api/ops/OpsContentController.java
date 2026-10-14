package ai.neargo.shop.content.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.content.ContentService;
import ai.neargo.shop.content.ContentService.MaterialVO;
import ai.neargo.shop.content.ContentService.PostVO;
import ai.neargo.shop.content.ContentService.QuestionVO;
import ai.neargo.shop.content.ContentService.RankingVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 内容与素材（P-15.1 / P-15.2）。
 *
 * <p>路径与入参<b>照抄 ops-web 现有调用</b>（`lib/api/https/content.ts`）。
 *
 * <p>三个列表返回 {@link PageData}（前端契约是 {@code Page<T>}），
 * <b>而榜单返回裸数组</b> —— 前端契约就是裸数组。两边形状必须逐条对，
 * 不能「统一包一层」：包错了同样是「接口 200、页面空白」。
 */
@Profile("ops")
@RestController
@Validated
public class OpsContentController {

    private final ContentService contentService;

    public OpsContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    // ---------------------------------------------------------------- 种草内容

    @GetMapping("/ops/contents/posts")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_READ + "')")
    public PageData<PostVO> posts(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String hasRisk,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long size) {
        Boolean risk = hasRisk == null || hasRisk.isBlank() ? null : Boolean.valueOf(hasRisk);
        return contentService.posts(status, risk, page, size);
    }

    @PostMapping("/ops/contents/posts/{postNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_AUDIT + "')")
    public PostVO decide(@PathVariable String postNo, @RequestBody DecideReq req) {
        return contentService.decidePost(postNo, req.to(), req.remark(),
                SecurityUtils.currentUserNo());
    }

    /** 命中风险词的内容不进批量 —— 单子里含命中项时**整批拒绝**。 */
    @PostMapping("/ops/contents/posts/batch-pass")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_AUDIT + "')")
    public List<PostVO> batchPass(@RequestBody BatchReq req) {
        return contentService.batchPass(req.postNos(), SecurityUtils.currentUserNo());
    }

    // ---------------------------------------------------------------- 问答

    @GetMapping("/ops/contents/questions")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_READ + "')")
    public PageData<QuestionVO> questions(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return contentService.questions(status, page, size);
    }

    @PostMapping("/ops/contents/questions/{questionNo}/answer")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_UPDATE + "')")
    public QuestionVO answer(@PathVariable String questionNo, @RequestBody AnswerReq req) {
        return contentService.answerQuestion(questionNo, req.answer(),
                SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/contents/questions/{questionNo}/hide")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_UPDATE + "')")
    public QuestionVO hide(@PathVariable String questionNo, @RequestBody ReasonReq req) {
        return contentService.hideQuestion(questionNo, req.reason(), SecurityUtils.currentUserNo());
    }

    // ---------------------------------------------------------------- 榜单

    /** 裸数组：前端契约是 {@code Ranking[]}，不是 {@code Page<Ranking>}。 */
    @GetMapping("/ops/contents/rankings")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_READ + "')")
    public List<RankingVO> rankings() {
        return contentService.rankings();
    }

    @PostMapping("/ops/contents/rankings")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_UPDATE + "')")
    public RankingVO saveRanking(@RequestBody RankingReq req) {
        return contentService.saveRanking(req.rankNo(), req.name(), req.kind(),
                req.size() == null ? 0 : req.size(), req.manualSkus(),
                Boolean.TRUE.equals(req.enabled()), SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/contents/rankings/{rankNo}/enabled")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_UPDATE + "')")
    public RankingVO setRankingEnabled(@PathVariable String rankNo, @RequestBody EnabledReq req) {
        return contentService.setRankingEnabled(rankNo, Boolean.TRUE.equals(req.enabled()),
                SecurityUtils.currentUserNo());
    }

    // ---------------------------------------------------------------- 素材

    @GetMapping("/ops/materials")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_READ + "')")
    public PageData<MaterialVO> materials(@RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return contentService.materials(kind, keyword, page, size);
    }

    @PostMapping("/ops/materials")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_UPDATE + "')")
    public MaterialVO saveMaterial(@RequestBody MaterialReq req) {
        return contentService.saveMaterial(req.materialNo(), req.title(), req.kind(),
                req.content(), req.scope(), req.scopeRefs(), req.langs(),
                SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/materials/{materialNo}/published")
    @PreAuthorize("@perm.can('" + Perms.CONTENT_MATERIAL_UPDATE + "')")
    public MaterialVO setPublished(@PathVariable String materialNo, @RequestBody PublishedReq req) {
        return contentService.setMaterialPublished(materialNo,
                Boolean.TRUE.equals(req.published()), SecurityUtils.currentUserNo());
    }

    // ---------------------------------------------------------------- 入参

    public record DecideReq(String to, String remark) {
    }

    public record BatchReq(List<String> postNos) {
    }

    public record AnswerReq(String answer) {
    }

    public record ReasonReq(String reason) {
    }

    public record RankingReq(String rankNo, String name, String kind, Integer size,
                             List<String> manualSkus, Boolean enabled) {
    }

    public record EnabledReq(Boolean enabled) {
    }

    public record MaterialReq(String materialNo, String title, String kind, String content,
                              String scope, List<String> scopeRefs, List<String> langs) {
    }

    public record PublishedReq(Boolean published) {
    }
}
