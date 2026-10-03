package com.springshop.common.excel;

import com.springshop.common.excel.task.ExcelTaskContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 大数据导出模板方法
 *
 * <p>四个导出（商品 / 管理员 / 操作日志 / 订单）除了「查什么、转成什么行模型」之外，
 * 流程完全一样：还原查询条件 → 建结果文件 → 分页拉取 → 逐页写盘 → 上报进度 → 收尾。
 * 把这套流程收口在这里，各业务只需要提供「取一页」这个动作。
 *
 * <p>不这样做的后果是每个导出各写一遍循环，而「最后一页不满要提前结束」
 * 「一条都没有也要写出表头」这类边界很容易在某一处漏掉。
 */
public final class ExcelExportSupport {

    private ExcelExportSupport() {
    }

    /**
     * 取一页数据
     *
     * @param query    导出查询条件（未传条件时为 null）
     * @param current  页码，从 1 开始
     * @param pageSize 每页条数
     */
    @FunctionalInterface
    public interface PageFetcher<Q, T> {

        List<T> fetch(Q query, long current, long pageSize);
    }

    /**
     * 执行分页流式导出
     *
     * @param context   任务上下文
     * @param queryType 查询条件类型（用于把任务里存的 JSON 还原成对象）
     * @param rowClass  导出行模型（字段用 {@code @ExcelProperty} 标注列名）
     * @param sheetName 工作表名
     * @param fetcher   分页取数
     * @return 实际导出的行数
     */
    public static <Q, T> int export(ExcelTaskContext context, Class<Q> queryType,
                                    Class<T> rowClass, String sheetName,
                                    PageFetcher<Q, T> fetcher) throws IOException {
        Q query = context.params(queryType);
        int pageSize = context.getProperties().getExportPageSize();
        Path output = context.createOutputFile();

        long exported = 0;
        boolean wroteAny = false;
        try (OutputStream out = Files.newOutputStream(output);
             ExcelStreamWriter writer = ExcelStreamWriter.ofClass(out, rowClass, sheetName)) {
            long current = 1;
            while (true) {
                List<T> records = fetcher.fetch(query, current, pageSize);
                if (records == null || records.isEmpty()) {
                    break;
                }
                writer.write(records);
                wroteAny = true;
                exported += records.size();
                context.reportProgress((int) exported, (int) exported, 0);
                if (records.size() < pageSize) {
                    // 最后一页不满，说明已经取完，省掉一次必然为空的查询
                    break;
                }
                current++;
            }
            if (!wroteAny) {
                // 一条都没查到也要写出表头：否则生成的是「连表头都没有」的损坏文件，
                // 用户打开是空白页，分不清是「没数据」还是「导出失败」
                writer.write(List.of());
            }
        }
        return (int) exported;
    }
}
