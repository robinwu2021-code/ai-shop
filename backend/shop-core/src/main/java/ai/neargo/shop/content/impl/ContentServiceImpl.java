package ai.neargo.shop.content.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.content.ContentService;
import ai.neargo.shop.content.entity.CntMaterial;
import ai.neargo.shop.content.entity.CntPost;
import ai.neargo.shop.content.entity.CntQuestion;
import ai.neargo.shop.content.entity.CntRanking;
import ai.neargo.shop.content.mapper.ContentMappers.MaterialMapper;
import ai.neargo.shop.content.mapper.ContentMappers.PostMapper;
import ai.neargo.shop.content.mapper.ContentMappers.QuestionMapper;
import ai.neargo.shop.content.mapper.ContentMappers.RankingMapper;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容与素材的实现。
 *
 * <p>八条业务规则的落点都在这里，每一条旁边写着它防的是什么 ——
 * 这些理由来自 ops-web 的契约注释，是需求本身。
 */
@Service
public class ContentServiceImpl implements ContentService {

    /**
     * 风险词库。<b>落 {@code sys_setting} 而不是单独建表</b>：
     * 一期是几十个词，与四类平台配置同一套机制。
     */
    static final String KEY_RISK_WORDS = "content.risk-words";

    private static final String DEFAULT_RISK_WORDS =
            "[\"加微信\",\"私聊\",\"代购\",\"转账\",\"最便宜\",\"第一\"]";

    /**
     * 允许的状态流转。<b>与前端的 {@code POST_TRANSITIONS} 是两份，且都要有</b>：
     * 前端那份为了不显示无效按钮，这一份为了防止真的发生。
     * 与 {@code OrderStateMachine} 同一口径。
     */
    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            CntPost.PENDING, List.of(CntPost.PASSED, CntPost.REJECTED),
            // PASSED → OFFLINE 是单独一条路，不能退回待审：
            // 内容已经露出过、可能已被引用，退回待审等于假装没发生过
            CntPost.PASSED, List.of(CntPost.OFFLINE),
            CntPost.REJECTED, List.of(),
            CntPost.OFFLINE, List.of());

    private final PostMapper postMapper;
    private final QuestionMapper questionMapper;
    private final RankingMapper rankingMapper;
    private final MaterialMapper materialMapper;
    private final GoodsQueryPort goodsQueryPort;
    private final SettingPort settingPort;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public ContentServiceImpl(PostMapper postMapper, QuestionMapper questionMapper,
                              RankingMapper rankingMapper, MaterialMapper materialMapper,
                              GoodsQueryPort goodsQueryPort, SettingPort settingPort,
                              AuditLogPort auditLogPort, ObjectMapper json) {
        this.postMapper = postMapper;
        this.questionMapper = questionMapper;
        this.rankingMapper = rankingMapper;
        this.materialMapper = materialMapper;
        this.goodsQueryPort = goodsQueryPort;
        this.settingPort = settingPort;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    // ---------------------------------------------------------------- 种草内容

    @Override
    public PageData<PostVO> posts(String status, Boolean hasRisk, long page, long size) {
        List<CntPost> all = postMapper.selectList(Wrappers.<CntPost>lambdaQuery()
                .eq(status != null && !status.isBlank(), CntPost::getStatus, status)
                .orderByDesc(CntPost::getId));
        List<PostVO> rows = all.stream()
                // 「有没有命中风险词」在库里是一个 JSON 列，筛选放在这里而不是 SQL：
                // 量级是审核队列（几百条），可读性比一条 JSON_LENGTH 更值钱
                .filter(p -> hasRisk == null || hasRisk == !readList(p.getRiskHits()).isEmpty())
                .map(this::toVO).toList();
        return PageData.ofAll(rows, page, size);
    }

    @Override
    @Transactional
    public PostVO decidePost(String postNo, String to, String remark, String operatorNo) {
        CntPost post = requirePost(postNo);
        assertTransit(post.getStatus(), to);
        // 驳回与下架必须写原因 —— 它原样回作者，不写等于让人猜
        if ((CntPost.REJECTED.equals(to) || CntPost.OFFLINE.equals(to))
                && (remark == null || remark.isBlank())) {
            throw BizException.of(ErrorCode.REASON_REQUIRED);
        }
        applyDecision(post, to, remark, operatorNo);
        return toVO(post);
    }

    @Override
    @Transactional
    public List<PostVO> batchPass(List<String> postNos, String operatorNo) {
        if (postNos == null || postNos.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<CntPost> posts = new ArrayList<>();
        for (String no : postNos) {
            CntPost p = requirePost(no);
            /*
             * **命中风险词的一律不进批量**，且整批拒绝而不是跳过它们。
             *
             * 跳过的话调用方拿到的是「成功」，而那几条还躺在待审里 ——
             * 静默跳过会让人以为全过了，等于把风险内容留在队列里没人再看。
             */
            if (!readList(p.getRiskHits()).isEmpty()) {
                throw BizException.of(ErrorCode.CONTENT_RISK_IN_BATCH, no);
            }
            assertTransit(p.getStatus(), CntPost.PASSED);
            posts.add(p);
        }
        List<PostVO> out = new ArrayList<>();
        for (CntPost p : posts) {
            // **逐条留痕**：批量是操作方式，不是审计粒度。
            // 出事时要能回答「这一条是谁放过去的」，而「批量通过 37 条」回答不了
            applyDecision(p, CntPost.PASSED, "批量通过", operatorNo);
            out.add(toVO(p));
        }
        return out;
    }

    private void applyDecision(CntPost post, String to, String remark, String operatorNo) {
        post.setStatus(to);
        post.setAuditRemark(remark);
        post.setAuditedBy(operatorNo);
        post.setAuditedAt(System.currentTimeMillis());
        postMapper.updateById(post);
        auditLogPort.record("CONTENT_POST_" + to, post.getPostNo(), remark);
    }

    private void assertTransit(String from, String to) {
        if (!TRANSITIONS.getOrDefault(from, List.of()).contains(to)) {
            throw BizException.of(ErrorCode.CONTENT_BAD_TRANSITION, from, to);
        }
    }

    // ---------------------------------------------------------------- 问答

    @Override
    public PageData<QuestionVO> questions(String status, long page, long size) {
        List<QuestionVO> rows = questionMapper.selectList(Wrappers.<CntQuestion>lambdaQuery()
                        .eq(status != null && !status.isBlank(), CntQuestion::getStatus, status)
                        .orderByDesc(CntQuestion::getId))
                .stream().map(this::toVO).toList();
        return PageData.ofAll(rows, page, size);
    }

    @Override
    @Transactional
    public QuestionVO answerQuestion(String questionNo, String answer, String operatorNo) {
        if (answer == null || answer.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        CntQuestion q = requireQuestion(questionNo);
        /*
         * **已回答的不能再答**。要改先隐藏 —— 让改动这件事本身留下痕迹。
         *
         * 允许覆盖的话，用户看到的答案变了，而没有任何地方记得它变过；
         * 而问答是会被截图的东西。
         */
        if (CntQuestion.ANSWERED.equals(q.getStatus())) {
            throw BizException.of(ErrorCode.CONTENT_ALREADY_ANSWERED);
        }
        q.setAnswer(answer);
        q.setAnsweredBy(operatorNo);
        q.setAnsweredAt(System.currentTimeMillis());
        q.setStatus(CntQuestion.ANSWERED);
        questionMapper.updateById(q);
        auditLogPort.record("CONTENT_QA_ANSWER", questionNo, answer);
        return toVO(q);
    }

    @Override
    @Transactional
    public QuestionVO hideQuestion(String questionNo, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.REASON_REQUIRED);
        }
        CntQuestion q = requireQuestion(questionNo);
        q.setStatus(CntQuestion.HIDDEN);
        q.setHideReason(reason);
        questionMapper.updateById(q);
        auditLogPort.record("CONTENT_QA_HIDE", questionNo, reason);
        return toVO(q);
    }

    // ---------------------------------------------------------------- 榜单

    @Override
    public List<RankingVO> rankings() {
        return rankingMapper.selectList(Wrappers.<CntRanking>lambdaQuery()
                .orderByDesc(CntRanking::getId)).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public RankingVO saveRanking(String rankNo, String name, String kind, int size,
                                 List<String> manualSkus, boolean enabled, String operatorNo) {
        if (name == null || name.isBlank() || kind == null || kind.isBlank() || size <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> skus = manualSkus == null ? List.of() : manualSkus;
        if (CntRanking.MANUAL.equals(kind)) {
            if (skus.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (skus.size() > size) {
                throw BizException.of(ErrorCode.CONTENT_RANKING_OVERSIZE, skus.size(), size);
            }
            /*
             * **人工榜的商品必须在售**：下架商品进了榜，用户点进去是空页 ——
             * 而榜单是首页流量最大的位置之一。
             *
             * 跨域读商品走 SPI（GoodsQueryPort），不直接依赖 product 域 ——
             * 与 CouponPort / PointsPort 同一个约束，否则 content 域再也拆不开。
             */
            Map<String, GoodsQueryPort.SkuSnapshot> snaps = goodsQueryPort.snapshot(skus);
            List<String> bad = skus.stream()
                    .filter(s -> snaps.get(s) == null || !snaps.get(s).onSale())
                    .toList();
            if (!bad.isEmpty()) {
                throw BizException.of(ErrorCode.CONTENT_RANKING_SKU_OFFLINE, String.join("、", bad));
            }
        } else if (!skus.isEmpty()) {
            // 非人工榜带了条目：传了就是调用方理解错了，静默忽略会让他一直以为配上了
            throw BizException.of(ErrorCode.CONTENT_RANKING_MANUAL_ONLY);
        }

        CntRanking r = rankNo == null || rankNo.isBlank() ? null : find(rankNo);
        if (r == null) {
            r = new CntRanking();
            r.setRankNo(BizKey.next(BizKey.RANKING));
        }
        r.setName(name);
        r.setKind(kind);
        r.setSize(size);
        r.setManualSkus(write(skus));
        r.setEnabled(enabled);
        if (r.getId() == null) {
            rankingMapper.insert(r);
        } else {
            rankingMapper.updateById(r);
        }
        auditLogPort.record("CONTENT_RANKING_SAVE", r.getRankNo(), name + "｜" + kind);
        return toVO(r);
    }

    @Override
    @Transactional
    public RankingVO setRankingEnabled(String rankNo, boolean enabled, String operatorNo) {
        CntRanking r = find(rankNo);
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        r.setEnabled(enabled);
        rankingMapper.updateById(r);
        auditLogPort.record("CONTENT_RANKING_ENABLED", rankNo, String.valueOf(enabled));
        return toVO(r);
    }

    private CntRanking find(String rankNo) {
        return rankingMapper.selectOne(Wrappers.<CntRanking>lambdaQuery()
                .eq(CntRanking::getRankNo, rankNo).last("LIMIT 1"));
    }

    // ---------------------------------------------------------------- 素材

    @Override
    public PageData<MaterialVO> materials(String kind, String keyword, long page, long size) {
        List<MaterialVO> rows = materialMapper.selectList(Wrappers.<CntMaterial>lambdaQuery()
                        .eq(kind != null && !kind.isBlank(), CntMaterial::getKind, kind)
                        .like(keyword != null && !keyword.isBlank(), CntMaterial::getTitle, keyword)
                        .orderByDesc(CntMaterial::getId))
                .stream().map(this::toVO).toList();
        return PageData.ofAll(rows, page, size);
    }

    @Override
    @Transactional
    public MaterialVO saveMaterial(String materialNo, String title, String kind, String content,
                                   String scope, List<String> scopeRefs, List<String> langs,
                                   String operatorNo) {
        if (title == null || title.isBlank() || kind == null || kind.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String sc = scope == null || scope.isBlank() ? CntMaterial.ALL : scope;
        List<String> refs = scopeRefs == null ? List.of() : scopeRefs;
        /*
         * 指定了社区或商家范围，投放对象就不能为空。
         * 「投给谁」和素材本身是一件事 —— 保存成功却谁都看不到，
         * 是那种「操作成功了，但后果不是你以为的那个」。
         */
        if (!CntMaterial.ALL.equals(sc) && refs.isEmpty()) {
            throw BizException.of(ErrorCode.CONTENT_SCOPE_REFS_REQUIRED);
        }
        CntMaterial m = materialNo == null || materialNo.isBlank() ? null
                : materialMapper.selectOne(Wrappers.<CntMaterial>lambdaQuery()
                        .eq(CntMaterial::getMaterialNo, materialNo).last("LIMIT 1"));
        if (m == null) {
            m = new CntMaterial();
            m.setMaterialNo(BizKey.next(BizKey.MATERIAL));
            m.setPublished(false);
            m.setDownloads(0);
        }
        m.setTitle(title);
        m.setKind(kind);
        m.setContent(content);
        m.setScope(sc);
        m.setScopeRefs(write(refs));
        m.setLangs(write(langs == null ? List.of() : langs));
        if (m.getId() == null) {
            materialMapper.insert(m);
        } else {
            materialMapper.updateById(m);
        }
        auditLogPort.record("CONTENT_MATERIAL_SAVE", m.getMaterialNo(), title);
        return toVO(m);
    }

    @Override
    @Transactional
    public MaterialVO setMaterialPublished(String materialNo, boolean published, String operatorNo) {
        CntMaterial m = materialMapper.selectOne(Wrappers.<CntMaterial>lambdaQuery()
                .eq(CntMaterial::getMaterialNo, materialNo).last("LIMIT 1"));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        m.setPublished(published);
        materialMapper.updateById(m);
        auditLogPort.record("CONTENT_MATERIAL_PUBLISHED", materialNo, String.valueOf(published));
        return toVO(m);
    }

    // ---------------------------------------------------------------- 风险词

    /**
     * 这段文字命中了哪些风险词。
     *
     * <p>结果由发布侧写进 {@code cnt_post.risk_hits}，<b>不在读取时现算</b> ——
     * 词库改了之后，「当时是不是命中了」还查得到。
     */
    public List<String> riskHitsOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return readList(settingPort.get(KEY_RISK_WORDS, DEFAULT_RISK_WORDS)).stream()
                .filter(text::contains).toList();
    }

    // ---------------------------------------------------------------- 助手

    private CntPost requirePost(String postNo) {
        CntPost p = postMapper.selectOne(Wrappers.<CntPost>lambdaQuery()
                .eq(CntPost::getPostNo, postNo).last("LIMIT 1"));
        if (p == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    private CntQuestion requireQuestion(String questionNo) {
        CntQuestion q = questionMapper.selectOne(Wrappers.<CntQuestion>lambdaQuery()
                .eq(CntQuestion::getQuestionNo, questionNo).last("LIMIT 1"));
        if (q == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return q;
    }

    /** 解析失败返回空表：一条写坏的 JSON 不该让整个列表打不开 */
    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String write(List<String> v) {
        return json.writeValueAsString(v);
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }

    private PostVO toVO(CntPost p) {
        return new PostVO(p.getPostNo(), p.getAuthorType(), p.getAuthorName(), p.getTitle(),
                p.getContent(), p.getCommunityNo(), p.getCommunityName(), p.getSkuNo(),
                readList(p.getRiskHits()), p.getStatus(), p.getAuditRemark(),
                p.getLikeCount() == null ? 0 : p.getLikeCount(), iso(p.getCreatedAt()));
    }

    private QuestionVO toVO(CntQuestion q) {
        return new QuestionVO(q.getQuestionNo(), q.getSkuNo(), q.getSkuTitle(), q.getContent(),
                q.getAskedBy(), q.getAnswer(), q.getAnsweredBy(), q.getAnsweredAt(),
                q.getStatus(), q.getHideReason(), iso(q.getCreatedAt()));
    }

    private RankingVO toVO(CntRanking r) {
        return new RankingVO(r.getRankNo(), r.getName(), r.getKind(),
                r.getSize() == null ? 0 : r.getSize(), readList(r.getManualSkus()),
                Boolean.TRUE.equals(r.getEnabled()), iso(r.getUpdatedAt()), r.getUpdatedBy());
    }

    private MaterialVO toVO(CntMaterial m) {
        return new MaterialVO(m.getMaterialNo(), m.getTitle(), m.getKind(), m.getContent(),
                m.getScope(), readList(m.getScopeRefs()), readList(m.getLangs()),
                Boolean.TRUE.equals(m.getPublished()),
                m.getDownloads() == null ? 0 : m.getDownloads(), iso(m.getCreatedAt()));
    }
}
