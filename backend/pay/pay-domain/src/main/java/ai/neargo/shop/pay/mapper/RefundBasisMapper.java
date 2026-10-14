package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.entity.StlBillRefundBasis;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 由paySqlSessionFactory装配，原值与结算单同一资金事务。 */
public interface RefundBasisMapper extends BaseMapper<StlBillRefundBasis> { }
