package com.springshop.common.excel.task.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.excel.task.ExcelFileStorage;
import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.mapper.ExcelTaskErrorMapper;
import com.springshop.common.excel.task.mapper.ExcelTaskMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Excel 任务台账服务测试
 *
 * <p>核心是一条不变量：<b>导出查询条件必须被可靠地持久化，失败要立刻报错</b>。
 *
 * <p>为什么这条不变量值得单独钉住：查询条件存在 {@code excel_task.params} 里，
 * 后台线程执行导出时再还原。一旦这条链路把「条件丢失」降级成「没有条件」，
 * 导出就会从「导出筛选结果」悄悄变成「导出整张表」——用户拿到的是一个
 * 看起来完全正常、任务状态还是「成功」的文件，没有任何报错信号。
 * 这是无声的数据事故，比直接失败危险得多。
 */
class ExcelTaskServiceImplTest {

    private ExcelTaskMapper excelTaskMapper;

    private ExcelTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        excelTaskMapper = mock(ExcelTaskMapper.class);
        service = new ExcelTaskServiceImpl(excelTaskMapper,
                mock(ExcelTaskErrorMapper.class),
                new ExcelFileStorage(new ExcelTaskProperties()),
                new ExcelTaskProperties(),
                new ObjectMapper());
    }

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
