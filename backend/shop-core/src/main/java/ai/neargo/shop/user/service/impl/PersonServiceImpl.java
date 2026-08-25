package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.entity.UsrPerson;
import ai.neargo.shop.user.entity.UsrPersonMergeLog;
import ai.neargo.shop.user.mapper.UserMappers.PersonMapper;
import ai.neargo.shop.user.mapper.UserMappers.PersonMergeLogMapper;
import ai.neargo.shop.user.service.PersonService;
import ai.neargo.shop.user.service.PhoneCrypto;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 见 {@link PersonService}。 */
@Service
public class PersonServiceImpl implements PersonService {

    private static final Logger log = LoggerFactory.getLogger(PersonServiceImpl.class);

    private final PersonMapper personMapper;
    private final PersonMergeLogMapper mergeLogMapper;
    private final PhoneCrypto crypto;
    private final ai.neargo.shop.user.mapper.UserMappers.UserMapper userMapper;

    /** 人档随账号注销一起作废。取值与 {@code UserServiceImpl.STATUS_DEREGISTERED} 一致 */
    private static final String DEREGISTERED_ACCOUNT = "DEREGISTERED";
    /** 人档自己的状态：已随账号注销 */
    private static final String DEREGISTERED = "DEREGISTERED";

    public PersonServiceImpl(PersonMapper personMapper, PersonMergeLogMapper mergeLogMapper,
                             PhoneCrypto crypto,
                             ai.neargo.shop.user.mapper.UserMappers.UserMapper userMapper) {
        this.personMapper = personMapper;
        this.mergeLogMapper = mergeLogMapper;
        this.crypto = crypto;
        this.userMapper = userMapper;
    }

    /**
     * <b>刻意不加 {@code @Transactional}</b>：这里的「插入撞唯一键就回读」在事务里会把
     * 外层事务标成 rollback-only，于是方法正常返回、提交时却抛 UnexpectedRollbackException ——
     * 表现是登录 500，而日志里看不出跟人档有关。这一段本来也只有单条语句，不需要事务。
     */
    @Override
    public UsrPerson resolveOrCreateByPhone(String phone) {
        String hash = crypto.hash(phone);
        if (hash == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        UsrPerson exist = byHash(hash);
        if (exist != null) {
            return exist;
        }
        UsrPerson p = new UsrPerson();
        p.setPersonNo(BizKey.next(BizKey.PERSON));
        p.setPhoneHash(hash);
        p.setPhoneEnc(crypto.encrypt(phone));
        p.setPhoneTail(crypto.tail(phone));
        p.setStatus(UsrPerson.ACTIVE);
        try {
            personMapper.insert(p);
        } catch (DuplicateKeyException e) {
            /*
             * 两个请求同时给同一个号建档 —— 唯一键挡下之后**回读那一份**，不是报错。
             * 商家在店里一边录一边有人下单，这个并发是常态。
             */
            UsrPerson raced = byHash(hash);
            if (raced == null) {
                throw e;
            }
            return raced;
        }
        return p;
    }

    @Override
    public Optional<UsrPerson> findByUser(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(personMapper.selectOne(Wrappers.<UsrPerson>lambdaQuery()
                .eq(UsrPerson::getUserNo, userNo)
                .eq(UsrPerson::getStatus, UsrPerson.ACTIVE)
                .last("limit 1")));
    }

    @Override
    public Optional<UsrPerson> find(String personNo) {
        if (personNo == null || personNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(personMapper.selectOne(Wrappers.<UsrPerson>lambdaQuery()
                .eq(UsrPerson::getPersonNo, personNo).last("limit 1")));
    }

    @Override
    public Optional<UsrPerson> bindOnLogin(String userNo, String phone) {
        String hash = crypto.hash(phone);
        if (userNo == null || userNo.isBlank() || hash == null) {
            // 微信登录没授权手机号：没有人档，也就不是任何商家的会员。这不是错误
            return Optional.empty();
        }
        UsrPerson byPhone = byHash(hash);

        // A 这个号还没有人档：建一份、直接绑上
        if (byPhone == null) {
            UsrPerson p = resolveOrCreateByPhone(phone);
            p.setUserNo(userNo);
            personMapper.updateById(p);
            return Optional.of(p);
        }
        // 已经是他自己的：什么都不用做（每次登录都会走到这里）
        if (userNo.equals(byPhone.getUserNo())) {
            return Optional.of(byPhone);
        }
        // C 绑着另一个账号：拒绝。允许自动合并等于「知道你手机号就能把你的账号并过来」
        if (byPhone.getUserNo() != null && !byPhone.getUserNo().isBlank()) {
            /*
             * 例外：那个账号已经注销（或干脆不存在了）。
             * 正常路径下注销会主动解绑（见 deregister），这里是**兜底** ——
             * 有一条历史脏数据，就会有一个人永远登不进来，而错误长得像「系统开小差」。
             */
            if (accountGone(byPhone.getUserNo())) {
                log.info("[person] 手机号原绑的账号 {} 已注销，改绑到 {}", byPhone.getUserNo(), userNo);
                byPhone.setUserNo(userNo);
                personMapper.updateById(byPhone);
                return Optional.of(byPhone);
            }
            log.warn("[person] 手机号已绑账号 {}，另一个账号 {} 想绑同一个号 —— 拒绝，走人工",
                    byPhone.getUserNo(), userNo);
            throw BizException.of(ErrorCode.PERSON_PHONE_TAKEN);
        }
        // B 有人档、没绑过账号（商家早就录过他）：直接绑，**不需要合并任何会员关系**
        byPhone.setUserNo(userNo);
        personMapper.updateById(byPhone);
        log.info("[person] 线索档 {} 被本人认领（账号 {}）", byPhone.getPersonNo(), userNo);
        return Optional.of(byPhone);
    }

    @Override
    @Transactional
    public void deregister(String userNo) {
        findByUser(userNo).ifPresent(p -> {
            /*
             * 让出手机号：不让的话，他用同一个号回来会撞唯一键，
             * 而那个错误长得像「系统开小差」，跟注销一点关系都看不出来。
             */
            p.setPhoneHash("deregistered:" + p.getPersonNo());
            p.setPhoneEnc(null);
            p.setPhoneTail(null);
            p.setUserNo(null);
            p.setStatus(DEREGISTERED);
            personMapper.update(null, Wrappers.<UsrPerson>lambdaUpdate()
                    .eq(UsrPerson::getPersonNo, p.getPersonNo())
                    .set(UsrPerson::getPhoneHash, p.getPhoneHash())
                    // 显式 set null：updateById 默认跳过 null 字段，那样个人信息一个也抹不掉
                    .set(UsrPerson::getPhoneEnc, null)
                    .set(UsrPerson::getPhoneTail, null)
                    .set(UsrPerson::getUserNo, null)
                    .set(UsrPerson::getStatus, DEREGISTERED));
            log.info("[person] 账号 {} 注销，人档 {} 解绑并让出手机号", userNo, p.getPersonNo());
        });
    }

    /** 那个账号还在不在、是不是已注销。查不到也当「没了」—— 兜底而不是纠结 */
    private boolean accountGone(String userNo) {
        var acc = userMapper.selectOne(Wrappers.<ai.neargo.shop.user.entity.UsrAccount>lambdaQuery()
                .eq(ai.neargo.shop.user.entity.UsrAccount::getUserNo, userNo).last("limit 1"));
        return acc == null || DEREGISTERED_ACCOUNT.equals(acc.getStatus());
    }

    @Override
    @Transactional
    public void merge(String fromPersonNo, String toPersonNo, String reason,
                      int affectedMembers, String operatorNo) {
        if (fromPersonNo == null || fromPersonNo.equals(toPersonNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        UsrPerson from = find(fromPersonNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        find(toPersonNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (UsrPerson.MERGED.equals(from.getStatus())) {
            return;     // 幂等：重复合并是空操作
        }
        from.setStatus(UsrPerson.MERGED);
        from.setMergedInto(toPersonNo);
        /*
         * 源档的手机号哈希要**让出来**：否则那个号永远查不到新档，
         * 而唯一键也会挡住这个人日后换回旧号。留 phone_tail 供排查。
         */
        from.setPhoneHash("merged:" + from.getPersonNo());
        personMapper.updateById(from);

        UsrPersonMergeLog logRow = new UsrPersonMergeLog();
        logRow.setFromPersonNo(fromPersonNo);
        logRow.setToPersonNo(toPersonNo);
        logRow.setReason(reason);
        logRow.setAffectedMembers(affectedMembers);
        logRow.setOperatorNo(operatorNo);
        logRow.setMergedAt(System.currentTimeMillis());
        mergeLogMapper.insert(logRow);
        log.warn("[person] 人档合并 {} → {}（{}），影响会员关系 {} 条",
                fromPersonNo, toPersonNo, reason, affectedMembers);
    }

    private UsrPerson byHash(String hash) {
        return personMapper.selectOne(Wrappers.<UsrPerson>lambdaQuery()
                .eq(UsrPerson::getPhoneHash, hash).last("limit 1"));
    }
}
