package com.springshop.common.excel.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.common.excel.task.ExcelTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Excel 任务 Mapper
 *
 * <p><b>为什么放在 {@code excel.task.mapper} 而不是 {@code excel.task}</b>：启动类上的
 * {@code @MapperScan("com.springshop.**.mapper")} 只匹配以 {@code mapper} 结尾的包，
 * 且显式声明 {@code @MapperScan} 会让 MyBatis 的「按 {@code @Mapper} 注解自动扫描」失效。
 * 放在约定目录下才能被注册成 bean，否则启动时直接报
 * 「No qualifying bean of type ExcelTaskMapper」。
 */
@Mapper
public interface ExcelTaskMapper extends BaseMapper<ExcelTask> {

    /**
     * 把卡在「待执行 / 执行中」的僵尸任务标记为失败
     *
     * <p>应用被 kill -9、容器被驱逐时，正在跑的任务来不及写终态，会永远停在「执行中」，
     * 前端就一直转圈。清理任务按创建时间兜底判定，把超期未结束的任务置为失败并给出原因。
     */
    @Update("UPDATE excel_task SET status = 3, error_msg = #{message}, end_time = CURRENT_TIMESTAMP "
            + "WHERE status IN (0, 1) AND create_time < #{deadline}")
    int failStaleTasks(@Param("deadline") LocalDateTime deadline, @Param("message") String message);

    /**
     * 查询已结束且仍挂着临时文件、且结束时间早于给定时刻的任务（用于清理磁盘）
     */
    @Select("SELECT task_no, file_path FROM excel_task "
            + "WHERE file_path IS NOT NULL AND status IN (2, 3) AND end_time IS NOT NULL AND end_time < #{deadline}")
    List<ExcelTask> selectExpiredFiles(@Param("deadline") LocalDateTime deadline);

    /**
     * 清空文件路径（磁盘文件已删除，避免清理任务反复重试同一个不存在的文件）
     */
    @Update("UPDATE excel_task SET file_path = NULL WHERE task_no = #{taskNo}")
    int clearFilePath(@Param("taskNo") String taskNo);

    /**
     * 统计某人某业务类型下未结束的任务数（用于防止重复提交把线程池打满）
     */
    @Select("SELECT COUNT(1) FROM excel_task WHERE created_by = #{adminId} AND biz_type = #{bizType} "
            + "AND status IN (0, 1)")
    long countRunningTasks(@Param("adminId") Long adminId, @Param("bizType") String bizType);
}
