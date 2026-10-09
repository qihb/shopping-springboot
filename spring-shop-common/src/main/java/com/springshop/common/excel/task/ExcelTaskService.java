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
     * <p><b>条件存不下来就拒绝建任务</b>，不会「降级成不带条件」：{@code params} 为 null
     * 会被执行侧解释成「不筛选」从而导出整张表，那等于把「条件丢了」变成无声的全量导出。
     *
     * @param query 导出查询条件对象，会序列化成 JSON 存起来供后台线程还原；
     *              传 {@code null} 表示显式要求「不筛选」（导出全量）
     * @throws com.springshop.common.exception.BusinessException 条件无法序列化时抛出，
     *         此时不会写入任何任务记录
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

    /**
     * 把任务从「待执行」推进到「执行中」（CAS）
     *
     * <p>带 {@code status = 0} 前置条件。影响 0 行说明任务已被别人处理过
     * （典型场景：清理任务已把它判为失败），<b>调用方必须放弃执行</b>——
     * 继续跑只会产生一份没人认领的结果。
     *
     * @return 影响行数，1 表示抢到，0 表示没抢到
     */
    int markRunning(String taskNo);

    /**
     * 回写执行进度（导入按批上报，导出按页上报）
     *
     * <p>只对「执行中」的任务生效。否则会给已经被判失败的任务继续回写进度，
     * 任务详情里就会出现「状态=失败，但成功 8000 行」这种自相矛盾的展示。
     *
     * @return 影响行数
     */
    int updateProgress(String taskNo, int processedRows, int successRows, int failRows);

    /**
     * 写成功终态（CAS，只对「执行中」的任务生效）
     *
     * <p>带 {@code status = 1} 前置条件。任务若已被清理任务判为失败，这里命中 0 行、
     * 不会把状态改回成功——否则用户会先被提示「任务中断，请重新提交」，
     * 重新提交之后原任务又变成「成功」，同一批数据进两遍。
     *
     * @return 影响行数，0 表示任务已是终态、本次写入被拒绝
     */
    int markSuccess(String taskNo, int processedRows, int successRows, int failRows);

    /**
     * 写失败终态（CAS，允许覆盖「待执行」与「执行中」）
     *
     * <p>之所以允许 {@code status = 0}：受理阶段落盘失败、线程池队列已满这两条路径
     * 都是在任务还没开始跑的时候写失败。但必须排除两个终态，
     * 否则会把清理任务写好的失败原因覆盖掉。
     *
     * @return 影响行数
     */
    int markFailed(String taskNo, String errorMsg);

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
