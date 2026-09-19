package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponIssue;
import ai.neargo.shop.promotion.entity.PmtCouponScope;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 营销域的 Mapper 集合（嵌套接口，与 {@code MemberMappers} 同一写法）。
 *
 * <p>Mapper 只做单表 CRUD 与条件组合 —— 一旦这里出现业务分支，数据域拦截器就会被绕过。
 */
public final class PromotionMappers {

    private PromotionMappers() {
    }

    public interface CouponMapper extends BaseMapper<PmtCoupon> {
    }

    /** 范围规则。**没有任何一行 = 全店**，与 {@code scope_type=ALL} 一致 */
    public interface CouponScopeMapper extends BaseMapper<PmtCouponScope> {
    }

    public interface UserCouponMapper extends BaseMapper<PmtUserCoupon> {
    }

    public interface CouponIssueMapper extends BaseMapper<PmtCouponIssue> {
    }

    public interface ActivityMapper extends BaseMapper<ai.neargo.shop.promotion.entity.PmtActivity> {
    }

    /** 受众。**一行都没有 = 对所有人生效** */
    public interface ActivityAudienceMapper
            extends BaseMapper<ai.neargo.shop.promotion.entity.PmtActivityAudience> {

        /**
         * <b>物理删这个活动的全部受众行。</b>理由与 {@link ActivityGoodsMapper#hardDeleteByActivity} 完全相同：
         * 唯一键 {@code uk_pmt_audience(tenant_no, activity_no, audience_type, audience_value)} 不含
         * {@code deleted}，逻辑删之后旧行仍占着那个组合 —— 编辑一个带受众的活动、受众不变，保存就撞键。
         * 商品那张表 2026-09-18 已经改过，受众这张当时漏了（2026-09-19 写人群受众时撞见）。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM pmt_activity_audience WHERE activity_no = #{activityNo}")
        int hardDeleteByActivity(@org.apache.ibatis.annotations.Param("activityNo") String activityNo);

        /** 物理删一行（合并标签时两个标签都在受众里，删掉源那一行）。理由同上 */
        @org.apache.ibatis.annotations.Delete("DELETE FROM pmt_activity_audience WHERE id = #{id}")
        int hardDeleteById(@org.apache.ibatis.annotations.Param("id") Long id);
    }

    /** 作用范围。按 ref_no 反查就是冲突提示要的那条路 */
    public interface ActivityGoodsMapper
            extends BaseMapper<ai.neargo.shop.promotion.entity.PmtActivityGoods> {

        /**
         * <b>物理删这个活动的全部作用范围行。</b>
         *
         * <p>为什么不能用 {@code delete(wrapper)}：那是逻辑删（{@code deleted = 1}），
         * 而唯一键 {@code uk_pmt_activity_goods(tenant_no, activity_no, scope_type, ref_no)}
         * <b>不含 {@code deleted}</b> —— 于是「整批换掉」这个写法在第二次保存时必然撞键：
         * 旧行还占着那个组合，新行插不进去。
         *
         * <p><b>2026-09-18 查实：任何活动只要编辑时保留原来那件商品，保存就会失败</b>，
         * 报的是 DuplicateKey，而界面上看起来像「保存没反应」。
         * 线上 {@code pmt_activity} 0 条，所以至今没有人撞到。
         *
         * <p>作用范围是<b>纯派生数据</b>（活动说了算，没有独立的历史价值），
         * 物理删是对的；优惠发生记录 {@code pmt_apply} 那种才需要留痕。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM pmt_activity_goods WHERE activity_no = #{activityNo}")
        int hardDeleteByActivity(@org.apache.ibatis.annotations.Param("activityNo") String activityNo);
    }

    /** 集单的一期。唯一键 (tenant_no, activity_no, period_date) 兜住并发建期 */
    public interface PeriodMapper extends BaseMapper<ai.neargo.shop.promotion.entity.PmtPeriod> {
    }

    /** 平台活动报名单。唯一键 (tenant_no, activity_no, entity_no)：一个商家对一个活动只报一次 */
    public interface EnrollmentMapper extends BaseMapper<ai.neargo.shop.promotion.entity.PmtEnrollment> {
    }

    public interface EnrollmentGoodsMapper extends BaseMapper<ai.neargo.shop.promotion.entity.PmtEnrollmentGoods> {

        /**
         * 物理删一份报名的货。理由同 {@link ActivityGoodsMapper#hardDeleteByActivity}：
         * 唯一键不含 deleted，逻辑删之后重报同一件货必撞键。报名的货是纯派生数据（报名单说了算）。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM pmt_enrollment_goods WHERE enrollment_no = #{enrollmentNo}")
        int hardDeleteByEnrollment(@org.apache.ibatis.annotations.Param("enrollmentNo") String enrollmentNo);
    }

    /** 自己组合的条件与优惠行 */
    public interface ActivityRuleMapper extends BaseMapper<ai.neargo.shop.promotion.entity.PmtActivityRule> {

        /** 物理删一个活动的全部组合行（整批换掉；理由同 ActivityGoodsMapper#hardDeleteByActivity） */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM pmt_activity_rule WHERE activity_no = #{activityNo}")
        int hardDeleteByActivity(@org.apache.ibatis.annotations.Param("activityNo") String activityNo);
    }

    /** 优惠发生记录。**只增不改**，撤销是往 {@code reverted_at} 上写一笔 */
    public interface ApplyMapper extends BaseMapper<PmtApply> {
    }
}
