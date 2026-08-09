package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.user.community.entity.CmtPickupPoint;
import ai.neargo.shop.user.mapper.UserMappers.PickupPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link PickupQueryPort} 实现：自提点的基本信息 + <b>计费口径</b>。
 *
 * <p>计费口径是给结算域用的：一笔子订单该不该付履约服务费、按件还是按率，
 * 全看这里返回的 {@code feeMode}（ADR-009 / V18）。
 */
@Component
public class PickupQueryPortImpl implements PickupQueryPort {

    /** 平台提供的自提点 —— 只有这一档才谈得上收费 */
    private static final String PLATFORM = "PLATFORM";
    private static final String NONE = "NONE";

    private final PickupPointMapper pickupMapper;

    public PickupQueryPortImpl(PickupPointMapper pickupMapper) {
        this.pickupMapper = pickupMapper;
    }

    @Override
    public Optional<PickupBrief> find(String pickupNo) {
        if (pickupNo == null || pickupNo.isBlank()) {
            return Optional.empty();
        }
        CmtPickupPoint p = pickupMapper.selectOne(Wrappers.<CmtPickupPoint>lambdaQuery()
                .eq(CmtPickupPoint::getPickupNo, pickupNo).last("limit 1"));
        if (p == null) {
            return Optional.empty();
        }
        return Optional.of(new PickupBrief(p.getPickupNo(), p.getName(), p.getAddress(),
                p.getType(), p.getCommunityNo(),
                feeModeOf(p), nz(p.getServiceFeePerItemMinor()), nzi(p.getServiceFeeRate())));
    }

    /**
     * 计费口径。<b>非 PLATFORM 一律 NONE，不看库里那两列的值</b>：
     *
     * <ul>
     *   <li>{@code STORE} 商家用自己的门店，平台不收履约费</li>
     *   <li>{@code NEIGHBOR} 邻里自提<b>零报酬</b>（ADR-005）—— 一旦能收钱，
     *       承接的邻居就是团长，ADR-004 消掉的合规问题会原样回来</li>
     * </ul>
     *
     * <p>在这里硬判而不是信任库里的值，是因为那两列可能被运营误填；
     * 判别只做一处，结算侧照单执行即可（判两遍等于两处都可能改错）。
     */
    private static String feeModeOf(CmtPickupPoint p) {
        if (!PLATFORM.equals(p.getType())) {
            return NONE;
        }
        String mode = p.getFeeMode();
        return mode == null || mode.isBlank() ? NONE : mode;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }
}
