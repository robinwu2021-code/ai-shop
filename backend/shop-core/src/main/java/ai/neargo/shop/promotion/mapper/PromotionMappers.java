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
    }

    /** 作用范围。按 ref_no 反查就是冲突提示要的那条路 */
    public interface ActivityGoodsMapper
            extends BaseMapper<ai.neargo.shop.promotion.entity.PmtActivityGoods> {
    }

    /** 优惠发生记录。**只增不改**，撤销是往 {@code reverted_at} 上写一笔 */
    public interface ApplyMapper extends BaseMapper<PmtApply> {
    }
}
