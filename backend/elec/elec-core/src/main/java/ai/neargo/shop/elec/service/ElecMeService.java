package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.MeDtos.MeView;

/** 一个账号在买家与供应商两面的身份与角标。端上启动与回到前台时调一次。 */
public interface ElecMeService {

    MeView me(String userNo);
}
