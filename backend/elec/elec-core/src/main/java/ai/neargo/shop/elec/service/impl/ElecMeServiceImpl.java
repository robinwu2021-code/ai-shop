package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.MeDtos.Badges;
import ai.neargo.shop.elec.dto.MeDtos.MeView;
import ai.neargo.shop.elec.dto.MeDtos.SupplierBrief;
import ai.neargo.shop.elec.entity.ElcDispatch;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.gateway.ElecAccounts;
import ai.neargo.shop.elec.mapper.ElecMappers.DispatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierStockStats;
import ai.neargo.shop.elec.service.ElecMeService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 身份与角标。「是不是供应商」由元器件自己的成员表决定，<b>不进令牌</b>：
 * 点了「成为供应商」之后下一次调这里就是供应商，不用重新登录。
 */
@ConditionalOnElec
@Service
public class ElecMeServiceImpl implements ElecMeService {

    private final ElecSupplierAccess access;
    private final ElecAccounts accounts;
    private final DispatchMapper dispatchMapper;
    private final StockMapper stockMapper;
    private final RfqMapper rfqMapper;

    public ElecMeServiceImpl(ElecSupplierAccess access, ElecAccounts accounts, DispatchMapper dispatchMapper,
                             StockMapper stockMapper, RfqMapper rfqMapper) {
        this.access = access;
        this.accounts = accounts;
        this.dispatchMapper = dispatchMapper;
        this.stockMapper = stockMapper;
        this.rfqMapper = rfqMapper;
    }

    @Override
    public MeView me(String userNo) {
        boolean phone = accounts.phone(userNo).isPresent();
        LocalDate today = LocalDate.now();
        int newOffers = (int) rfqMapper.countNewOffers(userNo, today);
        ElcSupplier s = access.of(userNo);
        if (s == null) {
            return new MeView(userNo, phone, null, new Badges(newOffers, 0, 0));
        }
        SupplierBrief brief = new SupplierBrief(s.getSupplierNo(), s.getCompanyName(), s.getStatus(), s.getMaskCode());
        if (!ElcSupplier.STATUS_ACTIVE.equals(s.getStatus())) {
            // 暂停只关供应商面：买家那个角标照给
            return new MeView(userNo, phone, brief, new Badges(newOffers, 0, 0));
        }
        int pending = dispatchMapper.selectCount(Wrappers.<ElcDispatch>lambdaQuery()
                .eq(ElcDispatch::getSupplierNo, s.getSupplierNo())
                .in(ElcDispatch::getStatus, ElcDispatch.STATUS_SENT, ElcDispatch.STATUS_VIEWED)).intValue();
        List<SupplierStockStats> st = stockMapper.statsBySupplier(List.of(s.getSupplierNo()), today,
                today.plusDays(ElecSupplierServiceImpl.EXPIRING_DAYS));
        int expiring = st.isEmpty() ? 0 : st.get(0).getExpiringCnt().intValue();
        return new MeView(userNo, phone, brief, new Badges(newOffers, pending, expiring));
    }
}
