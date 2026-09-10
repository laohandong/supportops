package io.supportops.user.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
/** 用户持久化及首次初始化的数据库互斥边界。 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    /** 锁定固定记录，将首次建管理员串行化；须在事务内调用。 */
    @Select("SELECT id FROM user_setup_lock WHERE id = 1 FOR UPDATE")
    int lockSetup();
}
