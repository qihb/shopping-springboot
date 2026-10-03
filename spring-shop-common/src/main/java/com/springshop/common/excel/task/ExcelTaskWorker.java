package com.springshop.common.excel.task;

/**
 * Excel 任务执行体
 *
 * <p>业务模块把「怎么导入 / 怎么导出」写成这个接口的实现（通常是 lambda），
 * 提交给 {@link ExcelTaskExecutor} 后由后台线程调用。
 *
 * <p>实现里<b>不要读写 {@code UserContext}</b>：执行发生在异步线程上，ThreadLocal 不会跨线程传递。
 * 提交人 id 通过 {@link ExcelTaskContext#getAdminId()} 获取。
 */
@FunctionalInterface
public interface ExcelTaskWorker {

    /**
     * 执行任务
     *
     * @param context 任务上下文：读源文件 / 建输出文件 / 上报进度 / 记录失败明细
     * @throws Exception 任意异常都会被执行器捕获、落成任务级失败原因，不会把线程池打挂
     */
    void run(ExcelTaskContext context) throws Exception;
}
