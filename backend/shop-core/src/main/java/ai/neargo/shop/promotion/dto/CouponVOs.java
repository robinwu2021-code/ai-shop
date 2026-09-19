package ai.neargo.shop.promotion.dto;

import java.util.List;

/** 券的 B 端视图与入参（P4）。 */
public final class CouponVOs {

    private CouponVOs() {
    }

    /**
     * 建券 / 改券。
     *
     * @param couponNo   空 = 新建；非空 = 改这一张
     * @param scopeRefs  {@code scopeType} 为 STORE/CATEGORY/GOODS 时的号列表
     * @param totalCount 发行量。<b>只有定向发放允许留空</b>（不限）——
     *                   领券中心的券不限量等于把敞口交给运气
     */
    public record CouponSaveCmd(String couponNo, String title, String benefitMode,
                                Long benefitValue, Long benefitCapMinor, String benefitRef,
                                Long minAmountMinor, Integer minQty,
                                String scopeType, List<String> scopeRefs, String scopeDesc,
                                String validityMode, Long startAt, Long endAt, Integer validDays,
                                String issueMode, String redeemMode, Integer timesTotal,
                                Integer totalCount, Integer perUserLimit, Long budgetMinor) {
    }

    /**
     * @param maxExposureMinor 最大敞口 = 发行量 × 单张最大优惠。
     *                         <b>建券页要显示它</b> —— 商家填「1000 张 × 20 元」时
     *                         心里想的是「发 1000 张」，不是「最多赔两万」
     * @param usedTimes  已核销次数（下单抵扣与到店核销都算，已回退的不算）。次卡按次、其余一张一次
     * @param spentMinor 已支出（分）：核销时实际减掉的钱之和，已回退的不算
     */
    public record CouponVO(String couponNo, String title, String benefitMode, Long benefitValue,
                           Long benefitCapMinor, String benefitRef,
                           Long minAmountMinor, Integer minQty,
                           String scopeType, List<String> scopeRefs, String scopeDesc,
                           String validityMode, Long startAt, Long endAt, Integer validDays,
                           String issueMode, String redeemMode, Integer timesTotal,
                           Integer totalCount, Integer receivedCount, Integer perUserLimit,
                           Long budgetMinor, Long maxExposureMinor, String status,
                           int usedTimes, long spentMinor) {
    }

    /**
     * 买家券包里的一张（C 端，P6）。
     *
     * @param redeemCode 到店出示的码。<b>只有 {@code STORE_CODE} 券有</b> ——
     *                   下单抵扣的券没有码，给它显示一个码会让顾客拿着手机去店里问
     * @param remaining  次卡还剩几次。一次性券是 1 或 0
     * @param usableNow  现在能不能用（没过期、没用完、券没被暂停）
     */
    public record MyCouponVO(String userCouponNo, String couponNo, String title,
                             String benefitText, String entityNo, String redeemMode,
                             String redeemCode, Long minAmountMinor,
                             int timesTotal, int timesUsed, int remaining,
                             long expireAt, String status, boolean usableNow,
                             /** 发券的店（原型 s24「张记粮油 · 全店」）；取不到时为空 */
                             String merchantName,
                             /** 适用范围一句话（「全店」「指定商品」）；由端上按 scopeType 说，这里给原值 */
                             String scopeType) {
    }

    /**
     * 一次发放的结果。
     *
     * @param skipped     跳过多少人。<b>它必须显示出来</b> ——
     *                    商家选了 37 个人、实发 25 张，只说「发放成功」的话，
     *                    他会以为发出去 37 张，直到某个顾客说没收到
     * @param skipReasons 每一类跳过多少：{@code ALREADY_HAS}（已达每人上限）、
     *                    {@code UNREACHABLE}（线索会员/已退订/还没注册）、
     *                    {@code SOLD_OUT}（券发完了）
     * @param audiences   发给了哪些受众项（按标签 / 分层 / 人群，取或）。旧批次只有 segmentNo，这里为空 ——
     *                    此前按标签发的批次在发放记录里会显示成「全部会员」，因为那一页只认 segmentNo
     */
    public record CouponIssueVO(String issueNo, String couponNo, String segmentNo,
                                int planned, int issued, int skipped,
                                List<SkipReason> skipReasons, long amountMinor,
                                String operatorNo, long issuedAt,
                                List<ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem> audiences) {

        public record SkipReason(String reason, int count) {
        }
    }
}
