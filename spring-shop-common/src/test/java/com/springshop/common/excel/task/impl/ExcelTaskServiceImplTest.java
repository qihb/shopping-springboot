package com.springshop.common.excel.task.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.task.ExcelFileStorage;
import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.ExcelTaskStatus;
import com.springshop.common.excel.task.mapper.ExcelTaskErrorMapper;
import com.springshop.common.excel.task.mapper.ExcelTaskMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Excel 任务台账服务测试
 *
 * <p>两条不变量：
 *
 * <ol>
 *   <li><b>导出查询条件必须被可靠地持久化，失败要立刻报错</b>。查询条件存在
 *       {@code excel_task.params} 里，后台线程执行导出时再还原。一旦这条链路把
 *       「条件丢失」降级成「没有条件」，导出就会从「导出筛选结果」悄悄变成
 *       「导出整张表」——用户拿到一个看起来完全正常、任务状态还是「成功」的文件，
 *       没有任何报错信号。这是无声的数据事故，比直接失败危险得多。</li>
 *   <li><b>终态写入必须带状态前置条件（CAS）</b>。清理任务会把卡住超过 24 小时的任务
 *       判为失败（{@code status = 3}）；如果那个任务的线程其实还活着，跑完后无条件
 *       UPDATE 会把状态从「失败」改回「成功」，用户先被告知「请重新提交」、
 *       重新提交后又多出一份数据。因为导入是纯新增，这就是实打实的重复数据。</li>
 * </ol>
 *
 * <p>第 2 条为什么断言「条件」而不是「结果」：本类是纯 Mockito 单测，mapper 被 mock，
 * {@code update} 返回什么完全由打桩决定，断言返回值没有意义。真正要钉住的是
 * <b>发出去的 UPDATE 语句带了什么 WHERE 条件、绑定了哪几个状态值</b>——
 * 这与项目里「缓存失效必须 verify delete 被调用」是同一类约定。
 */
class ExcelTaskServiceImplTest {

    private ExcelTaskMapper excelTaskMapper;

    private ExcelTaskErrorMapper excelTaskErrorMapper;

    private ExcelTaskServiceImpl service;

    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        // 预热 Lambda 列名缓存：纯单测环境没有 MP 的全量初始化，
        // 不预热的话 LambdaUpdateWrapper 解析 status 列名时会直接抛异常
        try {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                    ExcelTask.class);
        } catch (Exception ignore) {
            // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
        }
    }

    @BeforeEach
    void setUp() {
        excelTaskMapper = mock(ExcelTaskMapper.class);
        excelTaskErrorMapper = mock(ExcelTaskErrorMapper.class);
        service = new ExcelTaskServiceImpl(excelTaskMapper,
                excelTaskErrorMapper,
                new ExcelFileStorage(new ExcelTaskProperties()),
                new ExcelTaskProperties(),
                new ObjectMapper());
    }

    // ---------------- 导出条件必须可靠持久化 ----------------

    @Test
    void createExportTask_shouldPersistQueryAsJson() {
        DemoQuery query = new DemoQuery();
        query.setKeyword("手机");
        query.setStatus(1);

        ExcelTask task = service.createExportTask("DEMO_EXPORT", "演示导出", 1L, "导出.xlsx", query);

        assertNotNull(task.getParams(), "查询条件必须落库，否则后台只能按「无筛选」执行");
        assertTrue(task.getParams().contains("手机"), "落库的应当是条件本身的 JSON");
        verify(excelTaskMapper).insert(task);
    }

    @Test
    void createExportTask_shouldFailLoudlyWhenQueryCannotBeSerialized() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createExportTask("DEMO_EXPORT", "演示导出", 1L, "导出.xlsx",
                        new UnserializableQuery()),
                "条件存不下来时必须当场报错，不能降级成「无筛选」");

        assertEquals(ResultCode.EXCEL_TASK_PARAMS_INVALID.getCode(), ex.getCode());

        // 关键断言：绝不能留下一条 params 为空的导出任务。
        // 留下它 = 后台线程会把它当成「无筛选」执行 = 静默导出整张表。
        // 注意要显式写 ExcelTask.class：MyBatis-Plus 的 BaseMapper 同时有
        // insert(T) 与 insert(Collection<T>)，裸 any() 会因重载歧义而编译不过。
        verify(excelTaskMapper, never()).insert(any(ExcelTask.class));
    }

    // ---------------- 终态写入必须是 CAS ----------------

    @Test
    void markRunning_shouldOnlyClaimPendingTask() {
        when(excelTaskMapper.update(any(ExcelTask.class), any())).thenReturn(1);

        int affected = service.markRunning("I20261009120000abcd");

        assertEquals(1, affected, "应当把影响行数回给调用方，让调度器能判断是否真的抢到了任务");
        AbstractWrapper<ExcelTask, ?, ?> wrapper = captureUpdateWrapper();
        assertWhereMentions(wrapper, "status");
        assertEquals(Set.of(ExcelTaskStatus.PENDING.getCode()), boundStatuses(wrapper),
                "只有「待执行」的任务才能被领走：已被清理任务判为失败的任务不能被执行");
    }

    @Test
    void markSuccess_shouldOnlyApplyToRunningTask() {
        when(excelTaskMapper.update(any(ExcelTask.class), any())).thenReturn(1);

        int affected = service.markSuccess("I20261009120000abcd", 100, 100, 0);

        assertEquals(1, affected);
        AbstractWrapper<ExcelTask, ?, ?> wrapper = captureUpdateWrapper();
        assertWhereMentions(wrapper, "status");
        assertEquals(Set.of(ExcelTaskStatus.RUNNING.getCode()), boundStatuses(wrapper),
                "只允许「执行中 → 成功」：已经是终态的任务不能再被改写成成功");
    }

    @Test
    void markFailed_shouldNotBeAbleToOverwriteFinalState() {
        when(excelTaskMapper.update(any(ExcelTask.class), any())).thenReturn(1);

        int affected = service.markFailed("I20261009120000abcd", "文件保存失败，请重新提交");

        assertEquals(1, affected);
        AbstractWrapper<ExcelTask, ?, ?> wrapper = captureUpdateWrapper();
        assertWhereMentions(wrapper, "status");
        // 允许 0：受理阶段落盘失败、线程池队列已满这两条路径都是在任务还没开跑时写失败
        // 排除 2/3：终态不可被覆盖，否则会抹掉清理任务写好的失败原因
        assertEquals(Set.of(ExcelTaskStatus.PENDING.getCode(), ExcelTaskStatus.RUNNING.getCode()),
                boundStatuses(wrapper));
    }

    @Test
    void updateProgress_shouldOnlyApplyToRunningTask() {
        when(excelTaskMapper.update(any(ExcelTask.class), any())).thenReturn(1);

        service.updateProgress("I20261009120000abcd", 500, 500, 0);

        AbstractWrapper<ExcelTask, ?, ?> wrapper = captureUpdateWrapper();
        assertWhereMentions(wrapper, "status");
        assertEquals(Set.of(ExcelTaskStatus.RUNNING.getCode()), boundStatuses(wrapper),
                "不给已终态的任务回写进度，避免出现「状态=失败但成功 8000 行」的自相矛盾展示");
    }

    @Test
    void markSuccess_shouldReportZeroWhenTaskAlreadyFinalized() {
        // 模拟「任务已被清理任务判为失败」，此时 CAS 命中 0 行
        when(excelTaskMapper.update(any(ExcelTask.class), any())).thenReturn(0);

        int affected = service.markSuccess("I20261009120000abcd", 100, 100, 0);

        assertEquals(0, affected,
                "已经被判失败的任务不能再被改回成功，调用方据此决定只记日志、不改状态");
    }

    @Test
    void markSuccess_shouldClearErrorMsgThroughWrapper() {
        when(excelTaskMapper.update(any(ExcelTask.class), any())).thenReturn(1);

        service.markSuccess("I20261009120000abcd", 100, 100, 0);

        // MyBatis-Plus 默认 update-strategy = NOT_NULL，实体上的 null 字段会被整段跳过，
        // 所以「成功时清空错误原因」只能靠 wrapper.set(...) 显式声明。
        // 之前写成 update.setErrorMsg(null) 是无效的，error_msg 会一直留着旧失败原因。
        AbstractWrapper<ExcelTask, ?, ?> wrapper = captureUpdateWrapper();

        String sqlSet = wrapper.getSqlSet();
        assertNotNull(sqlSet, "成功终态必须显式声明 error_msg 的赋值，实际 SET 为 null");
        // 这里刻意不断言具体的列名拼写（"error_msg"）：本类的实体是在纯单测里手工预热
        // TableInfo 的，缺少 MP 的 tableUnderline 命名策略时解析出来是驼峰形式
        // （errorMsg），断言拼写会把测试绑死在测试脚手架的配置上，而不是绑在业务行为上。
        assertTrue(sqlSet.replace("_", "").toLowerCase().contains("errormsg"),
                "SET 片段里应当出现 error_msg 的赋值，实际为：" + sqlSet);

        // 比列名更本质的断言：被置空的那个参数确实绑成了 null。
        // 若退回 update.setErrorMsg(null)，NOT_NULL 会把整个字段跳过，
        // paramNameValuePairs 里根本不会有这个 null，本断言即失败 —— 这正是要钉住的行为。
        long nullParams = wrapper.getParamNameValuePairs().values().stream().filter(v -> v == null).count();
        assertEquals(1, nullParams,
                "markSuccess 应当且只应当把 error_msg 一个字段显式置空，实际绑定参数："
                        + wrapper.getParamNameValuePairs());
    }

    // ---------------- 失败明细被截断时必须让用户看得见 ----------------

    @Test
    void detail_shouldReportUnrecordedErrorRowsWhenDetailsAreTruncated() {
        ExcelTask task = taskWithFailures(12_345);
        when(excelTaskMapper.selectOne(any())).thenReturn(task);
        // 明细上限 10000：超出的部分只累加 failRows，不再落库
        when(excelTaskErrorMapper.countByTaskNo(task.getTaskNo())).thenReturn(10_000L);

        var vo = service.detail(task.getTaskNo(), 1L);

        assertEquals(12_345, vo.getFailRows().intValue());
        assertEquals(10_000, vo.getDetailRows().intValue(), "要告诉用户明细文件里实际有多少条");
        assertEquals(2_345, vo.getUnrecordedErrorRows().intValue(),
                "少记的条数必须回给前端：否则用户会把不完整的明细当成全部失败原因去对账");
    }

    @Test
    void detail_shouldReportZeroUnrecordedWhenDetailsAreComplete() {
        ExcelTask task = taskWithFailures(3);
        when(excelTaskMapper.selectOne(any())).thenReturn(task);
        when(excelTaskErrorMapper.countByTaskNo(task.getTaskNo())).thenReturn(3L);

        var vo = service.detail(task.getTaskNo(), 1L);

        assertEquals(0, vo.getUnrecordedErrorRows().intValue(),
                "明细完整时不该出现「已被截断」的误导提示");
    }

    @Test
    void detail_shouldNotCountErrorsWhenThereAreNoFailures() {
        ExcelTask task = taskWithFailures(0);
        when(excelTaskMapper.selectOne(any())).thenReturn(task);

        var vo = service.detail(task.getTaskNo(), 1L);

        assertEquals(0, vo.getDetailRows().intValue());
        assertEquals(0, vo.getUnrecordedErrorRows().intValue());
        // 零失败的导入任务与全部导出任务都走这条路，不该为此白查一次库
        verify(excelTaskErrorMapper, never()).countByTaskNo(any());
    }

    @Test
    void toVO_shouldNotQueryErrorDetails() {
        var vo = service.toVO(taskWithFailures(500));

        // toVO 同时服务「受理后直接返回」与「任务列表逐条转换」两条路径。
        // 一旦有人把 countErrors 挪进 toVO，任务列表就会变成 N 次查询
        assertTrue(vo.getDetailRows() == null && vo.getUnrecordedErrorRows() == null,
                "列表用的 VO 不该计算明细条数（null 表示「未计算」，0 会被误读成「没有明细」）");
        verify(excelTaskErrorMapper, never()).countByTaskNo(any());
    }

    /** 构造一条「已完成、带若干失败行」的任务，用于验证失败明细条数的计算 */
    private ExcelTask taskWithFailures(int failRows) {
        ExcelTask task = new ExcelTask();
        task.setTaskNo("I20261009120000test");
        task.setBizType("ADMIN_USER_IMPORT");
        task.setBizName("管理员导入");
        task.setTaskType(1);
        task.setStatus(ExcelTaskStatus.SUCCESS.getCode());
        task.setCreatedBy(1L);
        task.setFailRows(failRows);
        return task;
    }

    // ---------------- 辅助 ----------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWrapper<ExcelTask, ?, ?> captureUpdateWrapper() {
        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(excelTaskMapper).update(any(ExcelTask.class), captor.capture());
        return (AbstractWrapper<ExcelTask, ?, ?>) captor.getValue();
    }

    private void assertWhereMentions(AbstractWrapper<ExcelTask, ?, ?> wrapper, String column) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains(column), "UPDATE 的 WHERE 片段里应当出现 " + column + "，实际为：" + sql);
    }

    /**
     * WHERE 条件里绑定的整数值集合
     *
     * <p>wrapper 里只有 {@code taskNo}（String）与 {@code status}（Integer），
     * 所以筛出 Integer 就是「被当作状态条件绑定的值」。这样能精确断言
     * CAS 允许从哪几个状态出发，而不只是「有 status 条件」。
     */
    private Set<Integer> boundStatuses(AbstractWrapper<ExcelTask, ?, ?> wrapper) {
        return wrapper.getParamNameValuePairs().values().stream()
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .collect(Collectors.toSet());
    }

    /** 能被 Jackson 正常还原的导出条件 */
    public static class DemoQuery {

        private String keyword;

        private Integer status;

        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
    }

    /**
     * 模拟一个序列化必然失败的条件对象
     *
     * <p>真实场景里这种对象是存在的：getter 里做了惰性计算而计算抛异常、
     * 或者引用了不可序列化的运行时对象。用「getter 直接抛异常」是最稳定、
     * 不依赖 Jackson 具体版本的复现方式。
     */
    public static class UnserializableQuery {

        public String getKeyword() {
            throw new IllegalStateException("模拟序列化失败");
        }
    }
}
