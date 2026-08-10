package ai.neargo.shop.product.review.impl;

import ai.neargo.shop.product.review.ReviewService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.ReviewableOrderPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.review.dto.ReviewVO;
import ai.neargo.shop.product.review.entity.RvwAppeal;
import ai.neargo.shop.product.review.entity.RvwReview;
import ai.neargo.shop.product.review.entity.RvwReviewLike;
import ai.neargo.shop.product.review.mapper.ReviewMappers.AppealMapper;
import ai.neargo.shop.product.review.mapper.ReviewMappers.ReviewLikeMapper;
import ai.neargo.shop.product.review.mapper.ReviewMappers.ReviewMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评价实现。
 *
 * <p>两条规则由**库唯一键**兜底，服务端校验只是为了给出人话错误：
 * {@code uk_order_goods(sub_order_no, goods_no)} 挡重复评价，
 * {@code uk_review_user(review_no, user_no)} 挡重复点赞。
 * 只靠应用层判断的话，并发双击就能写进两条。
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    /** C 端只看得到审核通过的评价 —— 待审与被驳回的不能出现在商品页 */
    private static final String VISIBLE = "PASSED";

    private final ReviewMapper reviewMapper;
    private final ReviewLikeMapper likeMapper;
    private final AppealMapper appealMapper;
    private final ReviewableOrderPort orderPort;
    private final UserQueryPort userPort;
    private final ObjectMapper json;

    public ReviewServiceImpl(ReviewMapper reviewMapper, ReviewLikeMapper likeMapper,
                             AppealMapper appealMapper, ReviewableOrderPort orderPort,
                             UserQueryPort userPort, ObjectMapper json) {
        this.reviewMapper = reviewMapper;
        this.likeMapper = likeMapper;
        this.appealMapper = appealMapper;
        this.orderPort = orderPort;
        this.userPort = userPort;
        this.json = json;
    }

    @Override
    public List<ReviewVO> list(String goodsNo, String merchantNo) {
        // 两个都不传就是全表扫描，没有任何使用场景 —— 与契约注释一致，直接拒绝
        if (isBlank(goodsNo) && isBlank(merchantNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 评价对游客可见（看评价才有下单动机），所以要跳过数据域裁剪
        List<RvwReview> rows = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.selectList(Wrappers.<RvwReview>lambdaQuery()
                        .eq(RvwReview::getStatus, VISIBLE)
                        .eq(!isBlank(goodsNo), RvwReview::getGoodsNo, goodsNo)
                        .eq(!isBlank(merchantNo), RvwReview::getEntityNo, merchantNo)
                        .orderByDesc(RvwReview::getId)));

        Set<String> likedByMe = likedByCurrentUser(rows);
        Map<String, RvwAppeal> appeals = appealsOf(rows);
        return rows.stream().map(r -> toVO(r, likedByMe.contains(r.getReviewNo()),
                appeals.get(r.getReviewNo()))).toList();
    }

    @Override
    @Transactional
    public ReviewVO create(CreateCommand cmd) {
        String userNo = SecurityUtils.currentUserNo();
        if (cmd.rating() < 1 || cmd.rating() > 5) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        ReviewableOrderPort.ReviewableItem item = orderPort
                .findItem(cmd.orderNo(), cmd.goodsNo())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));   // 订单不存在 / 该单没买这个商品

        // 别人的单不能评 —— 订单号是可枚举的，不校验归属就是任意写入
        if (!userNo.equals(item.userNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        // 验收清单：「订单完成后才能评价」
        if (!item.completed()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // 验收清单：「该订单已评价」。库唯一键是最终防线，这里先给出可读的错误
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.exists(Wrappers.<RvwReview>lambdaQuery()
                        .eq(RvwReview::getSubOrderNo, item.subOrderNo())
                        .eq(RvwReview::getGoodsNo, cmd.goodsNo())));
        if (exists) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        RvwReview r = new RvwReview();
        r.setReviewNo(BizKey.next(BizKey.REVIEW));
        r.setSubOrderNo(item.subOrderNo());
        r.setOrderNo(cmd.orderNo());
        r.setGoodsNo(cmd.goodsNo());
        r.setSkuNo(item.skuNo());
        r.setEntityNo(item.merchantNo());
        r.setUserNo(userNo);
        // 昵称与头像存快照：用户改昵称不该让历史评价的署名跟着变
        r.setNickname(userPort.find(userNo).map(UserQueryPort.UserBrief::nickname).orElse("匿名用户"));
        r.setRating(cmd.rating());
        r.setContent(cmd.content());
        r.setImages(writeJson(cmd.images()));
        r.setSpec(item.spec());
        r.setLikeCount(0);
        r.setStatus(VISIBLE);
        if (cmd.scores() != null) {
            r.setScoreGoods(cmd.scores().goods());
            r.setScoreFulfillment(cmd.scores().fulfillment());
            r.setScoreService(cmd.scores().service());
        }
        reviewMapper.insert(r);
        return toVO(r, false, null);
    }

    @Override
    @Transactional
    public ReviewVO toggleLike(String reviewNo) {
        String userNo = SecurityUtils.currentUserNo();
        RvwReview r = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.selectOne(Wrappers.<RvwReview>lambdaQuery()
                        .eq(RvwReview::getReviewNo, reviewNo)));
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);   // 验收清单：「评价不存在」
        }

        RvwReviewLike existing = DataScopeContext.executeWithoutScope(() ->
                likeMapper.selectOne(Wrappers.<RvwReviewLike>lambdaQuery()
                        .eq(RvwReviewLike::getReviewNo, reviewNo)
                        .eq(RvwReviewLike::getUserNo, userNo)));

        boolean liked;
        if (existing == null) {
            RvwReviewLike like = new RvwReviewLike();
            like.setReviewNo(reviewNo);
            like.setUserNo(userNo);
            likeMapper.insert(like);
            liked = true;
        } else {
            likeMapper.deleteById(existing.getId());
            liked = false;
        }

        /*
         * like_count 是**派生值**，用明细重算而不是 +1/-1。
         * 增量更新在并发下会漂：两个人同时点赞，两次读到同一个旧值，各写回 +1，实际只加了 1。
         * 明细表才是 likeCount 的真源（V16 的表注释写明了这一点）。
         */
        long count = DataScopeContext.executeWithoutScope(() ->
                likeMapper.selectCount(Wrappers.<RvwReviewLike>lambdaQuery()
                        .eq(RvwReviewLike::getReviewNo, reviewNo)));
        r.setLikeCount((int) count);
        reviewMapper.updateById(r);
        return toVO(r, liked, appealsOf(List.of(r)).get(reviewNo));
    }

    // ---------------------------------------------------------------- 内部

    /** 一次查出当前用户对这批评价的点赞，避免逐条查（列表页 N+1 的经典来源） */
    private Set<String> likedByCurrentUser(List<RvwReview> rows) {
        String userNo = SecurityUtils.currentUserNoOrNull();
        if (userNo == null || rows.isEmpty()) {
            return Set.of();
        }
        List<String> nos = rows.stream().map(RvwReview::getReviewNo).toList();
        return DataScopeContext.executeWithoutScope(() ->
                likeMapper.selectList(Wrappers.<RvwReviewLike>lambdaQuery()
                                .eq(RvwReviewLike::getUserNo, userNo)
                                .in(RvwReviewLike::getReviewNo, nos))
                        .stream().map(RvwReviewLike::getReviewNo).collect(Collectors.toSet()));
    }

    private Map<String, RvwAppeal> appealsOf(List<RvwReview> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> nos = rows.stream().map(RvwReview::getReviewNo).toList();
        return DataScopeContext.executeWithoutScope(() ->
                appealMapper.selectList(Wrappers.<RvwAppeal>lambdaQuery()
                                .in(RvwAppeal::getReviewNo, nos))
                        .stream().collect(Collectors.toMap(RvwAppeal::getReviewNo, a -> a, (a, b) -> a)));
    }

    private ReviewVO toVO(RvwReview r, boolean liked, RvwAppeal appeal) {
        ReviewVO.Scores scores = r.getScoreGoods() == null ? null
                : new ReviewVO.Scores(r.getScoreGoods(), nz(r.getScoreFulfillment()), nz(r.getScoreService()));
        ReviewVO.Appeal appealVO = appeal == null ? null
                : new ReviewVO.Appeal(appeal.getAppealNo(), appeal.getReason(),
                appeal.getStatus(), appeal.getVerdict());
        return new ReviewVO(r.getReviewNo(), r.getGoodsNo(), r.getEntityNo(),
                r.getNickname(), r.getAvatar(), nz(r.getRating()), r.getContent(),
                readJson(r.getImages()), r.getSpec(), createdAtMillis(r),
                nz(r.getLikeCount()), liked, r.getReply(), scores, appealVO);
    }

    private long createdAtMillis(RvwReview r) {
        LocalDateTime t = r.getCreatedAt();
        return t == null ? 0L : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private List<String> readJson(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 存量脏数据不该让整个列表 500 —— 图片读不出来，评价正文照样有用
            return List.of();
        }
    }

    private String writeJson(List<String> images) {
        try {
            return json.writeValueAsString(Optional.ofNullable(images).orElse(List.of()));
        } catch (Exception e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ---------------------------------------------------------------- 商家侧（B-11.7）

    /** 低于这个分数才算差评，才允许申诉。三星是「一般」，不是差评。 */
    private static final int APPEALABLE_BELOW = 3;

    @Override
    public int pendingReplyCount(String merchantNo) {
        Long n = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.selectCount(Wrappers.<RvwReview>lambdaQuery()
                        .eq(RvwReview::getEntityNo, merchantNo)
                        .eq(RvwReview::getStatus, VISIBLE)
                        .isNull(RvwReview::getReply)));
        return n == null ? 0 : n.intValue();
    }

    @Override
    @Transactional
    public ReviewVO reply(String merchantNo, String reviewNo, String reply) {
        if (isBlank(reply)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        RvwReview r = ofMerchant(merchantNo, reviewNo);
        // 一条评价只能回一次：回复是公开表态，反复改会变成评论区里来回改口
        if (!isBlank(r.getReply())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        r.setReply(reply.trim());
        r.setRepliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> reviewMapper.updateById(r));
        return toVO(r, false, appealOf(reviewNo));
    }

    @Override
    @Transactional
    public ReviewVO appeal(String merchantNo, String reviewNo, String reason, List<String> images) {
        if (isBlank(reason)) {
            // 没有理由的申诉在裁决台上无法处理，只会变成一条永远待办的单
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        RvwReview r = ofMerchant(merchantNo, reviewNo);
        if (r.getRating() >= APPEALABLE_BELOW) {
            /*
             * 只有差评可申诉。放开的话「凡是不满意的评价都申诉一遍」，
             * 平台裁决台会被淹掉 —— 淹掉之后真正的恶意差评也没人看了。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (appealOf(reviewNo) != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        RvwAppeal a = new RvwAppeal();
        a.setAppealNo(BizKey.next(BizKey.APPEAL));
        a.setReviewNo(reviewNo);
        a.setEntityNo(merchantNo);
        a.setReason(reason.trim());
        a.setImages(writeJson(images));
        a.setStatus("PENDING");
        a.setSubmittedAt(System.currentTimeMillis());
        /*
         * 「一条评价只能申诉一次」的唯一键在库上（uk_review）——
         * 先查后插必然有竞态，而重复申诉在裁决台上是两条互相矛盾的待办。
         * 上面那次 appealOf 只是为了给出人话报错，不是防线。
         */
        DataScopeContext.executeWithoutScope(() -> appealMapper.insert(a));
        return toVO(r, false, a);
    }

    /** 取这条评价，并确认它属于这家店 —— 否则商家能回别家的评价。 */
    private RvwReview ofMerchant(String merchantNo, String reviewNo) {
        RvwReview r = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.selectOne(Wrappers.<RvwReview>lambdaQuery()
                        .eq(RvwReview::getReviewNo, reviewNo).last("limit 1")));
        if (r == null || !merchantNo.equals(r.getEntityNo())) {
            // 不区分「不存在」与「不是你的」：区分了就等于一个评价归属探测器
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return r;
    }

    private RvwAppeal appealOf(String reviewNo) {
        return DataScopeContext.executeWithoutScope(() ->
                appealMapper.selectOne(Wrappers.<RvwAppeal>lambdaQuery()
                        .eq(RvwAppeal::getReviewNo, reviewNo).last("limit 1")));
    }

    // ---------------------------------------------------------------- 平台治理（P-13.1）

    private static final String REJECTED = "REJECTED";
    private static final String PENDING = "PENDING";
    private static final String UPHELD = "UPHELD";
    private static final String DISMISSED = "DISMISSED";

    @Override
    public List<OpsReviewVO> opsList(String status, String merchantNo, String keyword) {
        List<RvwReview> rows = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.selectList(Wrappers.<RvwReview>lambdaQuery()
                        .eq(!isBlank(status), RvwReview::getStatus, status)
                        .eq(!isBlank(merchantNo), RvwReview::getEntityNo, merchantNo)
                        .like(!isBlank(keyword), RvwReview::getContent, keyword)
                        .orderByDesc(RvwReview::getId)));
        return rows.stream().map(this::toOpsVO).toList();
    }

    @Override
    @Transactional
    public OpsReviewVO decide(String reviewNo, boolean pass, String reason, String operatorNo) {
        if (!pass && isBlank(reason)) {
            // 与门店审核同一条规矩：驳回不写理由，被驳的人无从改起，只会反复提交同一份
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        RvwReview r = DataScopeContext.executeWithoutScope(() ->
                reviewMapper.selectOne(Wrappers.<RvwReview>lambdaQuery()
                        .eq(RvwReview::getReviewNo, reviewNo).last("limit 1")));
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        r.setStatus(pass ? VISIBLE : REJECTED);
        r.setRejectReason(pass ? null : reason.trim());
        DataScopeContext.executeWithoutScope(() -> reviewMapper.updateById(r));
        return toOpsVO(r);
    }

    @Override
    public List<OpsAppealVO> appeals(String status) {
        List<RvwAppeal> rows = DataScopeContext.executeWithoutScope(() ->
                appealMapper.selectList(Wrappers.<RvwAppeal>lambdaQuery()
                        .eq(!isBlank(status), RvwAppeal::getStatus, status)
                        .orderByDesc(RvwAppeal::getId)));
        return rows.stream().map(this::toAppealVO).toList();
    }

    @Override
    @Transactional
    public OpsAppealVO decideAppeal(String appealNo, boolean uphold, String verdict, String operatorNo) {
        if (isBlank(verdict)) {
            // 无论支持还是驳回都必须写：商家会看到它，「已读不处理」不是一种结果
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        RvwAppeal a = DataScopeContext.executeWithoutScope(() ->
                appealMapper.selectOne(Wrappers.<RvwAppeal>lambdaQuery()
                        .eq(RvwAppeal::getAppealNo, appealNo).last("limit 1")));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 裁完就是终态：再裁一次意味着同一条差评有两个结论，商家看到哪个取决于他什么时候刷新
        if (!PENDING.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        a.setStatus(uphold ? UPHELD : DISMISSED);
        a.setVerdict(verdict.trim());
        a.setDecidedAt(System.currentTimeMillis());
        a.setDecidedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> appealMapper.updateById(a));

        if (uphold) {
            /*
             * 支持商家 = 差评从 C 端消失。**改的是评价的状态，不是删除它** ——
             * 删了的话，同一个买家可以再评一条一模一样的，而平台看不出这是被裁过的。
             */
            RvwReview r = DataScopeContext.executeWithoutScope(() ->
                    reviewMapper.selectOne(Wrappers.<RvwReview>lambdaQuery()
                            .eq(RvwReview::getReviewNo, a.getReviewNo()).last("limit 1")));
            if (r != null) {
                r.setStatus(REJECTED);
                r.setRejectReason("申诉成立：" + verdict.trim());
                DataScopeContext.executeWithoutScope(() -> reviewMapper.updateById(r));
            }
        }
        return toAppealVO(a);
    }

    private OpsReviewVO toOpsVO(RvwReview r) {
        return new OpsReviewVO(r.getReviewNo(), r.getSubOrderNo(), r.getEntityNo(),
                // 商家名不在评价表上；平台列表按 merchantNo 展示，前端再去商家域取名
                r.getEntityNo(),
                r.getNickname(), nz(r.getRating()), nz(r.getScoreGoods()),
                nz(r.getScoreFulfillment()), nz(r.getScoreService()), r.getContent(),
                readJson(r.getImages()).size(), r.getStatus(), readJson(r.getRiskFlags()),
                r.getCreatedAt() == null ? 0L
                        : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                r.getRejectReason());
    }

    private OpsAppealVO toAppealVO(RvwAppeal a) {
        return new OpsAppealVO(a.getAppealNo(), a.getReviewNo(), a.getEntityNo(), a.getEntityNo(),
                a.getReason(), readJson(a.getImages()).size(), a.getStatus(),
                a.getSubmittedAt() == null ? 0L : a.getSubmittedAt(), a.getVerdict());
    }
}
