package com.springshop.common.excel.task;

import com.springshop.common.result.PageResult;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Excel 任务台账服务
 *
 * <p>只负责「任务记录本身」的增删改查与失败明细的存取，<b>不负责调度执行</b>
 * （执行在 {@link ExcelTaskExecutor}）。这样拆开是为了避免
 * 「服务依赖执行器、执行器又依赖服务」的循环依赖。
 */
public interface ExcelTaskService {

    /**
     * 建导入任务（待执行状态）
     *
     * @param storedPath 已落盘的源文件路径
     */
    ExcelTask createImportTask(String bizType, String bizName, Long adminId,
                               String originalFileName, String storedPath);

    /**
     * 建导出任务（待执行状态）
     *
     * @param query 导出查询条件对象，会序列化成 JSON 存起来供后台线程还原
     */
    ExcelTask createExportTask(String bizType, String bizName, Long adminId,
                               String fileName, Object query);

    /**
     * 校验同一业务类型下没有未结束的任务，防止连点把线程池打满
     *
     * @throws com.springshop.common.exception.BusinessException 已有同类任务在跑
     */
    void assertNotRunning(String bizType, Long adminId);

    /**
     * 按任务号查询，不存在抛业务异常
     */
    ExcelTask getByTaskNo(String taskNo);

    /**
     * 按任务号查询并校验归属：非本人任务一律按「不存在」处理，
     * 避免通过错误码差异探测出别人任务号是否存在
     */
    ExcelTask getOwnedTask(String taskNo, Long adminId);

    /**
     * 分页查询当前管理员自己的任务
     */
    PageResult<ExcelTaskVO> page(ExcelTaskPageQuery query, Long adminId);

    /**
     * 查询任务详情（含进度百分比与是否可下载），同样做归属校验
     */
    ExcelTaskVO detail(String taskNo, Long adminId);

    /**
     * 实体转 VO（受理任务后直接把 VO 返回给前端，省一次回查）
     */
    ExcelTaskVO toVO(ExcelTask task);

    void markRunning(String taskNo);

    /**
     * 回写执行进度（导入按批上报，导出按页上报）
     */
    void updateProgress(String taskNo, int processedRows, int successRows, int failRows);

    void markSuccess(String taskNo, int processedRows, int successRows, int failRows);

    void markFailed(String taskNo, String errorMsg);

    /**
     * 回写导出结果文件路径
     */
    void updateFilePath(String taskNo, String filePath);

    /**
     * 批量写入失败明细
     */
    void saveErrors(String taskNo, List<ExcelTaskError> errors);

    /**
     * 失败明细条数
     */
    long countErrors(String taskNo);

    /**
     * 把失败明细写成 xlsx 直接输出
     *
     * <p>失败明细不落盘而是「按需生成」：它本质上是 {@code excel_task_error} 的一个视图，
     * 落盘反而要额外考虑过期与清理；分页流式生成的内存占用同样恒定。
     */
    void writeErrorFile(String taskNo, OutputStream out) throws IOException;

    /**
     * 清理超过保留期的临时文件
     *
     * @return 删除的文件数
     */
    int cleanupExpiredFiles();

    /**
     * 把卡死的「待执行 / 执行中」任务标记为失败
     *
     * @return 影响行数
     */
    int failStaleTasks();
}
