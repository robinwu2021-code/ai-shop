package ai.neargo.shop.content;

import ai.neargo.shop.common.PageData;

import java.util.List;

/**
 * 内容与素材（P-15.1 / P-15.2）。
 *
 * <p><b>本域的业务规则此前只存在于 ops-web 的契约注释里</b>
 * （`contracts/content.ts`），后端零端点。那些注释不是前端的实现细节 ——
 * 它们把判断连同理由都写下来了，是这个域的需求。这里逐条实现，一条都不比它宽。
 *
 * <p>⚠️ 本批只做<b>平台侧的审核与治理</b>：C 端还不能发种草内容、不能提问，
 * 所以审核台会是空的。运营端的空态文案已写明「等 C 端发布链路接通」——
 * 与「有能力没有生产者」这个反复出现的形状是同一件事，区别只在于这次说出来了。
 */
public interface ContentService {

    // ---------------------------------------------------------------- 种草内容

    PageData<PostVO> posts(String status, Boolean hasRisk, long page, long size);

    /**
     * 裁决一条内容。
     *
     * <p>状态机在<b>服务端</b>强制，前端那份 {@code POST_TRANSITIONS} 是
     * 为了不显示无效按钮，这一份是为了防止真的发生 —— 两份都要有。
     *
     * <p>驳回与下架<b>必须写原因</b>：它原样回作者，不写等于让人猜。
     */
    PostVO decidePost(String postNo, String to, String remark, String operatorNo);

    /**
     * 批量通过。
     *
     * <p><b>命中风险词的内容一律不进批量</b> —— 批量 + 风险内容 = 事故。
     * 单子里含命中项时<b>整批拒绝而不是跳过它们</b>：静默跳过会让人以为全过了。
     *
     * <p>逐条留痕：批量是操作方式，不是审计粒度。出事时要能回答
     * 「这一条是谁放过去的」，而「批量通过 37 条」回答不了。
     */
    List<PostVO> batchPass(List<String> postNos, String operatorNo);

    // ---------------------------------------------------------------- 问答

    PageData<QuestionVO> questions(String status, long page, long size);

    /** 回答。<b>已回答的不能再答</b> —— 要改先隐藏，让改动本身留下痕迹。 */
    QuestionVO answerQuestion(String questionNo, String answer, String operatorNo);

    /** 隐藏（导流、辱骂等）。同样要写原因。 */
    QuestionVO hideQuestion(String questionNo, String reason, String operatorNo);

    // ---------------------------------------------------------------- 榜单

    List<RankingVO> rankings();

    /**
     * 建/改榜单。
     *
     * <p>{@code MANUAL} 与算出来的几类<b>校验路径完全不同</b>：
     * 前者必须有条目、条目数不超过 {@code size}、且商品<b>在售</b>
     * （下架商品进了榜，用户点进去是空页）；
     * 后者带了 {@code manualSkus} 直接拒绝 —— 传了就是调用方理解错了。
     */
    RankingVO saveRanking(String rankNo, String name, String kind, int size,
                          List<String> manualSkus, boolean enabled, String operatorNo);

    RankingVO setRankingEnabled(String rankNo, boolean enabled, String operatorNo);

    // ---------------------------------------------------------------- 素材

    PageData<MaterialVO> materials(String kind, String keyword, long page, long size);

    /**
     * 建/改素材。
     *
     * <p>指定社区或商家范围时，{@code scopeRefs} <b>不能为空</b> ——
     * 「投给谁」和素材本身是一件事，一份限定投放却没有投放对象的素材，
     * 保存成功了却谁都看不到。
     */
    MaterialVO saveMaterial(String materialNo, String title, String kind, String content,
                            String scope, List<String> scopeRefs, List<String> langs,
                            String operatorNo);

    MaterialVO setMaterialPublished(String materialNo, boolean published, String operatorNo);

    // ---------------------------------------------------------------- VO
    //
    // 字段名逐个对齐 ops-web 的类型（Post / Question / Ranking / Material）。

    record PostVO(String postNo, String authorType, String authorName, String title,
                  String content, String communityNo, String communityName, String skuNo,
                  List<String> riskHits, String status, String auditRemark,
                  int likeCount, String createdAt) {
    }

    record QuestionVO(String questionNo, String skuNo, String skuTitle, String content,
                      String askedBy, String answer, String answeredBy, Long answeredAt,
                      String status, String hideReason, String createdAt) {
    }

    record RankingVO(String rankNo, String name, String kind, int size,
                     List<String> manualSkus, boolean enabled,
                     String updatedAt, String updatedBy) {
    }

    record MaterialVO(String materialNo, String title, String kind, String content,
                      String scope, List<String> scopeRefs, List<String> langs,
                      boolean published, int downloads, String createdAt) {
    }
}
