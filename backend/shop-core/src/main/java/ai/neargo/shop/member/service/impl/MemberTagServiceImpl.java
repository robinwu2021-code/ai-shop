package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.MergePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.TagVO;
import ai.neargo.shop.member.entity.MbrMemberTag;
import ai.neargo.shop.member.entity.MbrTag;
import ai.neargo.shop.member.entity.MbrTagMergeLog;
import ai.neargo.shop.member.mapper.MemberMappers.MemberTagMapper;
import ai.neargo.shop.member.mapper.MemberMappers.TagMapper;
import ai.neargo.shop.member.mapper.MemberMappers.TagMergeLogMapper;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.spi.platform.SettingPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 见 {@link MemberTagService}。 */
@Service
public class MemberTagServiceImpl implements MemberTagService {

    private static final Logger log = LoggerFactory.getLogger(MemberTagServiceImpl.class);

    /** 口径入 sys_setting，代码里只有 key —— 运营要改上限不该等发版 */
    private static final String KEY_MAX_PER_MERCHANT = "member.tag.max-per-merchant";
    private static final String KEY_MAX_PER_MEMBER = "member.tag.max-per-member";
    private static final int DEFAULT_MAX_PER_MERCHANT = 50;
    private static final int DEFAULT_MAX_PER_MEMBER = 10;

    private final TagMapper tagMapper;
    private final MemberTagMapper memberTagMapper;
    private final TagMergeLogMapper mergeLogMapper;
    private final SettingPort settingPort;

    public MemberTagServiceImpl(TagMapper tagMapper, MemberTagMapper memberTagMapper,
                                TagMergeLogMapper mergeLogMapper, SettingPort settingPort) {
        this.tagMapper = tagMapper;
        this.memberTagMapper = memberTagMapper;
        this.mergeLogMapper = mergeLogMapper;
        this.settingPort = settingPort;
    }

    @Override
    public List<TagVO> tags(String entityNo) {
        return tagMapper.selectList(Wrappers.<MbrTag>lambdaQuery()
                        .eq(MbrTag::getEntityNo, entityNo)
                        .ne(MbrTag::getStatus, MbrTag.MERGED)
                        .orderByAsc(MbrTag::getId)).stream()
                .map(t -> new TagVO(t.getTagNo(), t.getName(), t.getTagType(), t.getStatus(),
                        countOf(t.getTagNo())))
                .toList();
    }

    @Override
    public List<TagVO> tagsOf(String entityNo, String memberNo) {
        List<MbrMemberTag> rows = memberTagMapper.selectList(Wrappers.<MbrMemberTag>lambdaQuery()
                .eq(MbrMemberTag::getEntityNo, entityNo)
                .eq(MbrMemberTag::getMemberNo, memberNo));
        List<TagVO> out = new ArrayList<>();
        for (MbrMemberTag r : rows) {
            MbrTag t = byNo(r.getTagNo());
            if (t != null) {
                out.add(new TagVO(t.getTagNo(), t.getName(), t.getTagType(), t.getStatus(), 0));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public TagVO create(String entityNo, String name, String operatorNo) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 32) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MbrTag exist = byName(entityNo, n);
        if (exist != null) {
            /*
             * 重名不报错，返回那一个。商家在两台设备上各建一次是常态，
             * 而「标签已存在」这句提示对他没有任何用处 —— 他要的就是这个标签。
             */
            if (MbrTag.DISABLED.equals(exist.getStatus())) {
                exist.setStatus(MbrTag.ACTIVE);
                tagMapper.updateById(exist);
            }
            return vo(exist);
        }
        long count = tagMapper.selectCount(Wrappers.<MbrTag>lambdaQuery()
                .eq(MbrTag::getEntityNo, entityNo)
                .eq(MbrTag::getTagType, MbrTag.MCH)
                .eq(MbrTag::getStatus, MbrTag.ACTIVE));
        if (count >= intSetting(KEY_MAX_PER_MERCHANT, DEFAULT_MAX_PER_MERCHANT)) {
            throw BizException.of(ErrorCode.MEMBER_TAG_LIMIT);
        }
        MbrTag t = new MbrTag();
        t.setTagNo(BizKey.next(BizKey.MEMBER_TAG));
        t.setEntityNo(entityNo);
        t.setName(n);
        t.setTagType(MbrTag.MCH);
        t.setStatus(MbrTag.ACTIVE);
        tagMapper.insert(t);
        return vo(t);
    }

    @Override
    @Transactional
    public TagVO rename(String entityNo, String tagNo, String name) {
        MbrTag t = require(entityNo, tagNo);
        assertNotSystem(t);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 32) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MbrTag dup = byName(entityNo, n);
        if (dup != null && !dup.getTagNo().equals(tagNo)) {
            // 改成一个已存在的名字，其实是「合并」—— 让他走合并那条路，那条会先给影响面
            throw BizException.of(ErrorCode.CONFLICT);
        }
        t.setName(n);
        tagMapper.updateById(t);
        return vo(t);
    }

    @Override
    @Transactional
    public TagVO setEnabled(String entityNo, String tagNo, boolean enabled) {
        MbrTag t = require(entityNo, tagNo);
        assertNotSystem(t);
        t.setStatus(enabled ? MbrTag.ACTIVE : MbrTag.DISABLED);
        tagMapper.updateById(t);
        return vo(t);
    }

    @Override
    @Transactional
    public MergePreviewVO merge(String entityNo, String fromTagNo, String intoTagNo,
                                boolean confirm, String operatorNo) {
        if (fromTagNo == null || fromTagNo.equals(intoTagNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MbrTag from = require(entityNo, fromTagNo);
        MbrTag into = require(entityNo, intoTagNo);
        assertNotSystem(from);
        assertNotSystem(into);

        List<MbrMemberTag> fromRows = rowsOf(fromTagNo);
        List<String> intoMembers = rowsOf(intoTagNo).stream()
                .map(MbrMemberTag::getMemberNo).toList();
        int both = (int) fromRows.stream()
                .filter(r -> intoMembers.contains(r.getMemberNo())).count();

        if (!confirm) {
            // 试算：把三个数摆给商家看，再让他按。合并不可逆
            return new MergePreviewVO(fromRows.size(), both, 0, false);
        }

        for (MbrMemberTag r : fromRows) {
            if (intoMembers.contains(r.getMemberNo())) {
                // 两个标签都有：删掉源那一条，否则唯一键会挡住改指
                memberTagMapper.deleteById(r.getId());
            } else {
                r.setTagNo(intoTagNo);
                memberTagMapper.updateById(r);
            }
        }
        from.setStatus(MbrTag.MERGED);
        from.setMergedInto(intoTagNo);
        // 源标签**保留不删**：活动受众与保存过的筛选条件可能还引用着它
        tagMapper.updateById(from);

        MbrTagMergeLog logRow = new MbrTagMergeLog();
        logRow.setEntityNo(entityNo);
        logRow.setFromTagNo(fromTagNo);
        logRow.setToTagNo(intoTagNo);
        logRow.setAffectedCount(fromRows.size());
        logRow.setOperatorNo(operatorNo);
        logRow.setMergedAt(System.currentTimeMillis());
        mergeLogMapper.insert(logRow);
        log.info("[member] 标签合并 {} → {}，影响 {} 人（其中 {} 人两个都有）",
                fromTagNo, intoTagNo, fromRows.size(), both);
        return new MergePreviewVO(fromRows.size(), both, 0, true);
    }

    @Override
    @Transactional
    public void tag(String entityNo, List<String> memberNos, List<String> add, List<String> remove,
                    String operatorNo) {
        if (memberNos == null || memberNos.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> toAdd = add == null ? List.of() : add;
        List<String> toRemove = remove == null ? List.of() : remove;
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (String tagNo : toAdd) {
            assertNotSystem(require(entityNo, tagNo));
        }
        for (String tagNo : toRemove) {
            assertNotSystem(require(entityNo, tagNo));
        }

        int maxPerMember = intSetting(KEY_MAX_PER_MEMBER, DEFAULT_MAX_PER_MEMBER);
        for (String memberNo : memberNos) {
            for (String tagNo : toRemove) {
                memberTagMapper.delete(Wrappers.<MbrMemberTag>lambdaQuery()
                        .eq(MbrMemberTag::getMemberNo, memberNo)
                        .eq(MbrMemberTag::getTagNo, tagNo));
            }
            for (String tagNo : toAdd) {
                boolean has = memberTagMapper.exists(Wrappers.<MbrMemberTag>lambdaQuery()
                        .eq(MbrMemberTag::getMemberNo, memberNo)
                        .eq(MbrMemberTag::getTagNo, tagNo));
                if (has) {
                    continue;   // 重复打标是常态（先筛后打，人群会重叠），不该报错
                }
                long owned = memberTagMapper.selectCount(Wrappers.<MbrMemberTag>lambdaQuery()
                        .eq(MbrMemberTag::getMemberNo, memberNo)
                        .eq(MbrMemberTag::getTagType, MbrTag.MCH));
                if (owned >= maxPerMember) {
                    throw BizException.of(ErrorCode.MEMBER_TAG_LIMIT);
                }
                MbrMemberTag row = new MbrMemberTag();
                row.setEntityNo(entityNo);
                row.setMemberNo(memberNo);
                row.setTagNo(tagNo);
                row.setTagType(MbrTag.MCH);
                row.setTaggedBy(operatorNo);
                row.setTaggedAt(System.currentTimeMillis());
                memberTagMapper.insert(row);
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 系统标签只读。
     *
     * <p><b>它的名字就是口径</b>：允许改名或手动打之后，两个商家对「沉睡」会有两种理解，
     * 而按它筛出来的人群从此不可比 —— 那时再想收回来，已经有人照着它发过券了。
     */
    private static void assertNotSystem(MbrTag t) {
        if (MbrTag.SYS.equals(t.getTagType())) {
            throw BizException.of(ErrorCode.MEMBER_TAG_SYSTEM_READONLY);
        }
    }

    private MbrTag require(String entityNo, String tagNo) {
        MbrTag t = tagMapper.selectOne(Wrappers.<MbrTag>lambdaQuery()
                .eq(MbrTag::getEntityNo, entityNo)
                .eq(MbrTag::getTagNo, tagNo).last("limit 1"));
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private MbrTag byNo(String tagNo) {
        return tagMapper.selectOne(Wrappers.<MbrTag>lambdaQuery()
                .eq(MbrTag::getTagNo, tagNo).last("limit 1"));
    }

    private MbrTag byName(String entityNo, String name) {
        return tagMapper.selectOne(Wrappers.<MbrTag>lambdaQuery()
                .eq(MbrTag::getEntityNo, entityNo)
                .eq(MbrTag::getName, name).last("limit 1"));
    }

    private List<MbrMemberTag> rowsOf(String tagNo) {
        return memberTagMapper.selectList(Wrappers.<MbrMemberTag>lambdaQuery()
                .eq(MbrMemberTag::getTagNo, tagNo));
    }

    private int countOf(String tagNo) {
        Long n = memberTagMapper.selectCount(Wrappers.<MbrMemberTag>lambdaQuery()
                .eq(MbrMemberTag::getTagNo, tagNo));
        return n == null ? 0 : n.intValue();
    }

    private TagVO vo(MbrTag t) {
        return new TagVO(t.getTagNo(), t.getName(), t.getTagType(), t.getStatus(),
                countOf(t.getTagNo()));
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(settingPort.get(key, String.valueOf(fallback)).trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
