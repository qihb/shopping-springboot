package com.springshop.common.excel.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.common.excel.task.ExcelTaskError;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * Excel 任务失败明细 Mapper
 *
 * <p>与 {@link ExcelTaskMapper} 同理，必须放在 {@code *.mapper} 包下才能被
 * {@code @MapperScan("com.springshop.**.mapper")} 扫到。
 */
@Mapper
public interface ExcelTaskErrorMapper extends BaseMapper<ExcelTaskError> {

    /**
     * 批量写入失败明细
     *
     * <p>单行 insert 循环在一万条明细下要发一万次 SQL，这里按批拼一条多值 INSERT，
     * 与导入本身的批处理节奏一致（{@code excel.task.import-batch-size}）。
     */
    @Insert("<script>"
            + "INSERT INTO excel_task_error (task_no, row_num, message, create_time) VALUES "
            + "<foreach collection='errors' item='e' separator=','>"
            + "(#{e.taskNo}, #{e.rowNum}, #{e.message}, CURRENT_TIMESTAMP)"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("errors") Collection<ExcelTaskError> errors);

    /**
     * 按行号顺序分页读取失败明细（下载失败明细文件时按批流式写出，内存恒定）
     */
    @Select("SELECT id, task_no, row_num, message, create_time FROM excel_task_error "
            + "WHERE task_no = #{taskNo} ORDER BY row_num, id LIMIT #{limit} OFFSET #{offset}")
    List<ExcelTaskError> selectPageByTaskNo(@Param("taskNo") String taskNo,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    /**
     * 统计某任务的失败明细条数
     */
    @Select("SELECT COUNT(1) FROM excel_task_error WHERE task_no = #{taskNo}")
    long countByTaskNo(@Param("taskNo") String taskNo);
}
