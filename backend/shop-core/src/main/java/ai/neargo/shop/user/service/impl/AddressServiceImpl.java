package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.AddressService;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.AddressVO;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.mapper.UserMappers.AddressMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.entity.UsrAccount;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class AddressServiceImpl implements AddressService {

    private final AddressMapper addressMapper;
    /** 生效位置存在 usr_account 上（用户级单值），所以这里要它 */
    private final UserMapper userMapper;

    public AddressServiceImpl(AddressMapper addressMapper, UserMapper userMapper) {
        this.addressMapper = addressMapper;
        this.userMapper = userMapper;
    }

    @Override
    public List<AddressVO> list() {
        return rows().stream().map(AddressVO::forOwner).toList();
    }

    @Override
    @Transactional
    public List<AddressVO> save(SaveCommand cmd) {
        boolean creating = cmd.addressId() == null || cmd.addressId().isBlank();
        /*
         * 只拦**新增**。编辑已有的那条永远放行 —— 存量用户可能已经超了上限
         * （上限是这次才有的），拦住编辑等于让他连自己的手机号都改不了，
         * 而他做的事跟「地址太多」毫无关系。
         */
        if (creating && rows().size() >= MAX_ADDRESSES) {
            throw BizException.of(ErrorCode.ADDRESS_LIMIT_EXCEEDED, MAX_ADDRESSES);
        }
        UsrAddress row = creating ? newRow() : requireOwn(cmd.addressId());

        row.setName(cmd.name());
        row.setPhone(cmd.phone());
        row.setRegion(cmd.region());
        row.setProvince(cmd.province());
        row.setCity(cmd.city());
        row.setDistrict(cmd.district());
        row.setDetail(cmd.detail());
        row.setHouseNo(cmd.houseNo());
        // 两个都给才写：只来一半是端上的 bug，写进去会得到一个落在赤道或本初子午线上的收货地址
        if (cmd.latE6() != null && cmd.lngE6() != null) {
            row.setLatE6(cmd.latE6());
            row.setLngE6(cmd.lngE6());
        }
        row.setTag(cmd.tag());

        // 首个地址自动成为默认：否则用户加完第一个地址去下单，还要回来手动设一次
        boolean makeDefault = Boolean.TRUE.equals(cmd.isDefault()) || rows().isEmpty();
        row.setIsDefault(makeDefault);

        if (row.getId() == null) {
            addressMapper.insert(row);
        } else {
            addressMapper.updateById(row);
        }
        if (makeDefault) {
            clearOtherDefaults(row.getAddressId());
        }
        return list();
    }

    @Override
    @Transactional
    public List<AddressVO> archive(String addressId) {
        UsrAddress row = requireOwn(addressId);
        addressMapper.deleteById(row.getId());   // 逻辑删除
        return list();
    }

    @Override
    @Transactional
    public List<AddressVO> setDefault(String addressId) {
        UsrAddress row = requireOwn(addressId);
        row.setIsDefault(true);
        addressMapper.updateById(row);
        clearOtherDefaults(addressId);
        return list();
    }

    @Override
    public AddressVO activeAddress() {
        UsrAccount me = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        String id = me == null ? null : me.getActiveAddressId();
        if (id == null || id.isBlank()) {
            return null; // 新用户没有位置 —— 不是异常，首页照常有东西看
        }
        UsrAddress row = addressMapper.selectOne(Wrappers.<UsrAddress>lambdaQuery()
                .eq(UsrAddress::getAddressId, id)
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        /*
         * **指向的地址被删了就当作没有**，不要抛。
         * 用户在地址簿里删掉了当前生效的那条是完全正常的操作，
         * 而让首页因此报错、或让他从此打不开首页，是把一个正常动作变成故障。
         */
        return row == null ? null : AddressVO.forOwner(row);
    }

    @Override
    public AddressVO switchActiveAddress(String addressId) {
        UsrAddress row = requireOwn(addressId); // 顺带挡住「切到别人的地址」
        userMapper.update(null, Wrappers.<UsrAccount>lambdaUpdate()
                .eq(UsrAccount::getUserNo, SecurityUtils.currentUserNo())
                .set(UsrAccount::getActiveAddressId, addressId));
        // 刻意不碰 isDefault：它回答的是另一个问题（下单预填谁）
        return AddressVO.forOwner(row);
    }

    /**
     * 「至多一条默认」的落点。用 UPDATE 批量清而不是逐条读改：
     * 逐条读改在并发下会留下两条默认（两个请求各自读到对方之前的状态）。
     */
    private void clearOtherDefaults(String keepAddressId) {
        UsrAddress patch = new UsrAddress();
        patch.setIsDefault(false);
        addressMapper.update(patch, Wrappers.<UsrAddress>lambdaUpdate()
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .ne(UsrAddress::getAddressId, keepAddressId)
                .eq(UsrAddress::getIsDefault, true));
    }

    private UsrAddress newRow() {
        UsrAddress row = new UsrAddress();
        row.setAddressId(BizKey.next(BizKey.ADDRESS));
        row.setUserNo(SecurityUtils.currentUserNo());
        return row;
    }

    /**
     * 属主校验：查询条件带 {@code userNo}，查不到即 404（而不是先查出来再比对）。
     * 先查后比会让「别人的地址」和「不存在的地址」返回不同的错误 —— 那本身就是一种信息泄漏。
     */
    private UsrAddress requireOwn(String addressId) {
        UsrAddress row = addressMapper.selectOne(Wrappers.<UsrAddress>lambdaQuery()
                .eq(UsrAddress::getAddressId, addressId)
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    /**
     * 列表顺序：**默认 → 有坐标 → 最近改动过的**。
     *
     * <p>此前是「默认 → id 倒序」，也就是越新加的越靠前 ——
     * 一个用了半年的家会被上周填的一个临时地址压下去。
     *
     * <p>「有坐标的在前」不是排版偏好：没有坐标的那些，商家的自送半径判不了、
     * 导航也打不开，把它们排在前面等于优先推荐一条会出问题的地址。
     *
     * <p>后两档在 Java 里排而不是写进 SQL：「坐标非空」在 H2 与 MariaDB 上
     * 要写成不同的表达式，而这张表一个人至多 {@value AddressService#MAX_ADDRESSES} 行，
     * 为它引一处方言差异不划算。
     */
    private List<UsrAddress> rows() {
        List<UsrAddress> rows = addressMapper.selectList(Wrappers.<UsrAddress>lambdaQuery()
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .orderByDesc(UsrAddress::getIsDefault)
                .orderByDesc(UsrAddress::getId));
        return rows.stream()
                .sorted(Comparator
                        .comparing((UsrAddress a) -> Boolean.TRUE.equals(a.getIsDefault())).reversed()
                        .thenComparing(Comparator.comparing(
                                (UsrAddress a) -> a.getLatE6() != null && a.getLngE6() != null).reversed())
                        .thenComparing(Comparator.comparing(
                                UsrAddress::getUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())).reversed()))
                .toList();
    }
}
