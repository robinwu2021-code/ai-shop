package ai.neargo.shop.user.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.mapper.UserMappers.MerchantMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 商家域对外的两个 Port：查询（{@link MerchantQueryPort}）与开通（{@link MerchantAdminPort}）。
 *
 * <p>合成一个实现类而不是两个，因为它们共用同一张表与同一套字段口径；
 * 分开写会出现两处各自维护「什么算 ACTIVE」的判断。接口仍是两个 ——
 * 查询是所有域都用的，开通只有 platform（运营审核通过）能调，权限边界不同。
 *
 * <p>从 {@code MerchantServiceImpl} 抽出：Service 兼任 Port 时，
 * 改本域的商家详情逻辑会不知不觉改掉 trade / settle 依赖的跨域契约。
 */
@Component
public class MerchantPortImpl implements MerchantQueryPort, MerchantAdminPort {

    private static final String ACTIVE = "ACTIVE";
    /** 评分存整数（50 = 5.0 分），避免浮点入库 */
    private static final int RATING_SCALE = 10;
    private static final int RATING_INIT = 50;

    private final MerchantMapper merchantMapper;

    public MerchantPortImpl(MerchantMapper merchantMapper) {
        this.merchantMapper = merchantMapper;
    }

    @Override
    public Optional<MerchantBrief> find(String merchantNo) {
        UsrMerchant m = merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                .eq(UsrMerchant::getMerchantNo, merchantNo).last("limit 1"));
        if (m == null) {
            return Optional.empty();
        }
        /*
         * canReceive 目前等同于 ACTIVE；S6 接分账后改为「接收方已报备」（ADR-002）。
         * 调用方（trade / settle）届时一行不用改 —— 这正是 Port 的作用：
         * 「能不能收钱」这个判断的口径变了，问的人不必知道。
         */
        boolean active = ACTIVE.equals(m.getStatus());
        return Optional.of(new MerchantBrief(m.getMerchantNo(), m.getName(), active, active,
                m.getLogo(), m.getRating() == null ? 0d : m.getRating() / (double) RATING_SCALE,
                Boolean.TRUE.equals(m.getVerified()),
                m.getBreachCount() == null ? 0 : m.getBreachCount()));
    }

    @Override
    @Transactional
    public String activate(String ownerUserNo, String name, String type) {
        // 已是商家就不重复创建 —— 审核通过被重复点击是常态，不是异常
        UsrMerchant existing = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                        .eq(UsrMerchant::getOwnerUserNo, ownerUserNo).last("limit 1")));
        if (existing != null) {
            existing.setStatus(ACTIVE);
            merchantMapper.updateById(existing);
            return existing.getMerchantNo();
        }

        UsrMerchant m = new UsrMerchant();
        m.setMerchantNo(BizKey.next(BizKey.MERCHANT));
        m.setName(name);
        m.setLogo("");
        m.setType(type == null ? "PERSONAL" : type);
        m.setOwnerUserNo(ownerUserNo);
        // 新店从中位分起步，不是 0 分 —— 0 分会让新店在任何按评分排的列表里垫底，
        // 而它还没有任何订单可以证明自己（ADR-009 里「平台推荐位」要解决的也是这个问题）
        m.setRating(RATING_INIT);
        m.setRatingCount(0);
        m.setSalesCount(0);
        m.setGoodsCount(0);
        m.setScoreGoods(RATING_INIT);
        m.setScoreService(RATING_INIT);
        m.setScoreSpeed(RATING_INIT);
        m.setVerified(true);   // 审核通过即带认证标
        m.setBreachCount(0);
        m.setTags("[]");
        m.setJoinedAt(System.currentTimeMillis());
        m.setStatus(ACTIVE);
        merchantMapper.insert(m);
        return m.getMerchantNo();
    }
}
