package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.AddressVO;

import java.util.List;

/**
 * 地址簿（[API 清单 §2.2] / R1）。**送货上门与快递两条履约线的前置**。
 *
 * <p>四个方法**全部返回整个列表**而不是单条：端上每次操作后都要刷新列表，
 * 返回单条会逼前端自己合并状态 —— 而「设为默认」这类操作会同时改动两条记录，
 * 前端合不对就会出现两个默认地址的假象。
 */
public interface AddressService {

    /**
     * 一个人最多存几条地址。**对标值**（美团那边也是二十上下）。
     *
     * <p>不设限的后果不是「占空间」，是结算页那个选地址的列表变得没法用 ——
     * 而地址是只增不减的东西：没人会回头去删。
     *
     * <p>端上也有一份（`ADDRESS_RULES.maxCount`），那是为了把「新增」按钮提前置灰；
     * 这一份才是真正的闸 —— 还没更新的老版本 App 不知道有这回事。
     */
    int MAX_ADDRESSES = 20;

    List<AddressVO> list();

    /** {@code addressId} 为空即新增。首个地址自动成为默认。 */
    List<AddressVO> save(SaveCommand cmd);

    /** 软删除（契约禁止 delete*）。 */
    List<AddressVO> archive(String addressId);

    List<AddressVO> setDefault(String addressId);

    /**
     * 当前生效位置。**没有就是 null**，那不是异常：新用户一个位置都没有，
     * 首页照常要有东西看，而不是空白等他去选。
     */
    AddressVO activeAddress();

    /**
     * 切换当前生效位置。**不动 {@code is_default}** ——
     * 默认是「下单预填哪个收货人」，生效是「现在按哪儿看货」，两者会不一样。
     */
    AddressVO switchActiveAddress(String addressId);

    /**
     * @param houseNo 门牌号（V319）。与 {@code detail} 是两件事：detail 是地址主体
     *                （选点页给的，带坐标），houseNo 是最后 50 米（只能手打）。
     * @param latE6 收货地址坐标（gcj02，E6）。<b>null = 这次不改</b> ——
     *              老版本端上不传这两个字段，把缺省当清空会把已标好的点抹掉。
     *              这两列 V1 就建了（注释写着「配送范围校验用」），但在此之前<b>全链路无人写入</b>，
     *              于是「商家自送半径」这个设置一直算不出任何东西
     */
    record SaveCommand(String addressId, String name, String phone, String region,
                       String province, String city, String district, String detail,
                       String houseNo,
                       Boolean isDefault, String tag,
                       Integer latE6, Integer lngE6) {

        /** 不带坐标、也不带门牌的老形状 */
        public SaveCommand(String addressId, String name, String phone, String region,
                           String province, String city, String district, String detail,
                           Boolean isDefault, String tag) {
            this(addressId, name, phone, region, province, city, district, detail,
                    null, isDefault, tag, null, null);
        }
    }
}
