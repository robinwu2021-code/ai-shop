package ai.neargo.shop.member.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReachRecipientMapper {
    @Insert("""
        <script>INSERT INTO mbr_reach_recipient(task_no, member_no) VALUES
        <foreach collection="members" item="member" separator=",">(#{taskNo}, #{member})</foreach></script>
        """)
    void insertCandidates(@Param("taskNo") String taskNo, @Param("members") List<String> members);

    @Select("""
        SELECT member_no FROM mbr_reach_recipient
        WHERE task_no=#{taskNo} AND outcome='PENDING' ORDER BY member_no LIMIT 1
        """)
    String next(String taskNo);

    @Update("""
        UPDATE mbr_reach_recipient SET outcome=#{outcome}, reach_no=#{reachNo}
        WHERE task_no=#{taskNo} AND member_no=#{memberNo} AND outcome='PENDING'
        """)
    void finish(@Param("taskNo") String taskNo, @Param("memberNo") String memberNo,
                @Param("outcome") String outcome, @Param("reachNo") String reachNo);
}
