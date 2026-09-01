package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.payclient.BizPointsAppService;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import org.springframework.stereotype.Service;

@Service
public class BizPointsAppServiceImpl implements BizPointsAppService {

    private final MerchantAdminPort merchantAdmin;
    private final PointsService pointsService;

    public BizPointsAppServiceImpl(MerchantAdminPort merchantAdmin, PointsService pointsService) {
        this.merchantAdmin = merchantAdmin;
        this.pointsService = pointsService;
    }

    @Override
    public MerchantPointAccountVO toggleMerchant(String merchantNo, boolean enabled) {
        /*
         * 顺序：先改开关，再读账户 —— 读到的就是改完之后的状态。
         * 反过来的话返回的是旧状态，而端上会照着它渲染，
         * 于是「点了开关但界面没变」，用户会再点一次。
         *
         * 两步之间没有事务：改开关在商家域、读账户在支付域，
         * 而**读失败不影响开关已经改成功这个事实** —— 端上重新拉一次就对了。
         */
        merchantAdmin.setPointsEnabled(merchantNo, enabled);
        return pointsService.merchantAccount(merchantNo);
    }
}
