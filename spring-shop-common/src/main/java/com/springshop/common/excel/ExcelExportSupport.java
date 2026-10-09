package com.springshop.common.excel;

import com.springshop.common.excel.task.ExcelTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * 大数据导出模板方法
 *
 * <p>四个导出（商品 / 管理员 / 操作日志 / 订单）除了「查什么、转成什么行模型」之外，
 * 流程完全一样：还原查询条件 → 建结果文件 → 分页拉取 → 逐页写盘 → 上报进度 → 收尾。
 * 把这套流程收口在这里，各业务只需要提供「取一页」这个动作。
 *
 * <p>不这样做的后果是每个导出各写一遍循环，而「最后一页不满要提前结束」
 * 「一条都没有也要写出表头」这类边界很容易在某一处漏掉。
 *
 * <p><b>分页方式：keyset（游标），不是 OFFSET</b>。见 {@link #export} 的注释。
 */
public final class ExcelExportSupport {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportSupport.class);

    private ExcelExportSupport() {
    }

    /**
     * 取一页数据（keyset / 游标分页）
     *
     * <p>实现方必须<b>按 id 排序并按游标过滤</b>，且排序方向要与
     * {@code lastId} 的比较方向一致：
     * <ul>
     *   <li>倒序导出（{@code ORDER BY id DESC}）→ 下一页取 {@code id < lastId}；</li>
     *   <li>正序导出（{@code ORDER BY id ASC}）→ 下一页取 {@code id > lastId}。</li>
     * </ul>
     *
     * @param query    导出查询条件（未传条件时为 null）
     * @param lastId   上一页最后一条的 id；{@code null} 表示从第一页开始
     * @param pageSize 每页条数
     */
    @FunctionalInterface
    public interface PageFetcher<Q, T> {

        List<T> fetch(Q query, Long lastId, long pageSize);
    }

    /**
     * 执行分页流式导出
     *
     * <p><b>为什么用 keyset 而不是 OFFSET</b>：导出动辄几十万行、要跑几分钟，
     * 期间有新的订单/日志/商品进来是常态。{@code LIMIT n OFFSET m} 是「跳过前 m 行」，
     * 一旦有人往前面插了数据，后面每一页的窗口都会整体后移：已经导出的行被重复导出，
     * 而原本排在窗口边缘的行则可能被整页跳过去，永远不会出现在结果里。
     * 游标分页只认「比上一页最后一条更小（或更大）的 id」，窗口不会因为别人插数据而移动。
     *
     * <p>顺带还解决了深分页的性能问题：{@code OFFSET 400000} 要求数据库先扫过并丢掉
     * 前 40 万行，而 {@code WHERE id < 游标} 可以直接走主键索引定位。
     *
     * <p>游标建立在 <b>id 唯一</b> 的前提上：排序键不唯一时，相邻两页边界上的同值行
     * 会被重复或漏掉。所以调用方传进来的 {@code idOf} 必须取到行的主键，
     * 且各业务的查询必须是「按 id 单列排序」。
     *
     * @param context   任务上下文
     * @param queryType 查询条件类型（用于把任务里存的 JSON 还原成对象）
     * @param rowClass  导出行模型（字段用 {@code @ExcelProperty} 标注列名）
     * @param sheetName 工作表名
     * @param idOf      从行模型取出主键，作为下一页的游标
     * @param fetcher   分页取数
     * @return 实际导出的行数
     */
    public static <Q, T> int export(ExcelTaskContext context, Class<Q> queryType,
                                    Class<T> rowClass, String sheetName,
                                    Function<T, Long> idOf, PageFetcher<Q, T> fetcher) throws IOException {
        Q query = context.params(queryType);
        int pageSize = context.getProperties().getExportPageSize();
        Path output = context.createOutputFile();

        long exported = 0;
        boolean wroteAny = false;
        try (OutputStream out = Files.newOutputStream(output);
             ExcelStreamWriter writer = ExcelStreamWriter.ofClass(out, rowClass, sheetName)) {
            Long lastId = null;
            while (true) {
                List<T> records = fetcher.fetch(query, lastId, pageSize);
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
                Long nextId = idOf.apply(records.get(records.size() - 1));
                if (nextId == null || nextId.equals(lastId)) {
                    // 游标没推进：要么行里取不到主键，要么取数实现压根没按游标过滤。
                    // 不拦的话下一页会取回同一批数据，任务会一直写同一个文件直到磁盘写满
                    log.warn("导出分页游标未推进，提前结束以避免重复导出（任务号 {}，已导出 {} 行）",
                            context.getTaskNo(), exported);
                    break;
                }
                lastId = nextId;
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
