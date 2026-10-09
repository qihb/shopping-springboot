package com.springshop.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskService;
import com.springshop.common.excel.task.ExcelTaskStatus;
import com.springshop.common.excel.task.mapper.ExcelTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Excel 任务状态流转（CAS）的真实数据库验证
 *
 * <p><b>为什么单测之外还要这一层</b>：{@code ExcelTaskServiceImplTest} 是纯 Mockito 单测，
 * mapper 被 mock 掉，只能断言「发出去的 UPDATE 带了什么 WHERE 条件」，
 * <b>证明不了这个条件真的会拦住写入</b>——SQL 拼错、命名策略没生效、
 * 条件被 MyBatis 丢弃，单测全都发现不了。本类连真实的 H2（MySQL 兼容模式）跑，
 * 直接读回数据库里的行来断言结果，补上这一环。
 *
 * <p>顺带覆盖一个纯单测永远覆盖不到的点：{@code markSuccess} 用
 * {@code wrapper.set(ExcelTask::getErrorMsg, null)} 清空失败原因。
 * MyBatis-Plus 默认的 {@code update-strategy = NOT_NULL} 会把实体上的 null 字段整段跳过，
 * 只有走 wrapper 才能真的清空；而这条 SQL 到底能不能在真库上执行成功
 * （null 参数的 jdbcType 绑定、列名解析），必须实跑才知道。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：{@code markRunning / markSuccess} 等方法
 * 都是 {@code REQUIRES_NEW}，会自己开事务并立即提交，测试事务对它们没有意义；
 * 加了反而会让「回写是否真的落库」变得不可见。测试数据按 taskNo 唯一隔离，
 * 并在 {@link #cleanup()} 里删干净。
 *
 * <p>注解组合与 {@link ExcelTaskIntegrationTest} 保持一致（含未使用的
 * {@code @AutoConfigureMockMvc}），这样两个类能复用同一个 Spring 上下文，
 * 不必为多跑一个测试类再启动一次应用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExcelTaskCasIntegrationTest {

    /** excel_task.created_by 非空，随便给一个归属人即可（本类不走归属校验） */
    private static final long ADMIN_ID = 1L;

    @Autowired
    private ExcelTaskService excelTaskService;

    @Autowired
    private ExcelTaskMapper excelTaskMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private final List<String> createdTaskNos = new ArrayList<>();

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void cleanup() {
        for (String taskNo : createdTaskNos) {
            excelTaskMapper.delete(Wrappers.<ExcelTask>lambdaQuery().eq(ExcelTask::getTaskNo, taskNo));
        }
        createdTaskNos.clear();
    }

    // ---------------- 终态不可被改写 ----------------

    @Test
    void markSuccess_shouldNotResurrectFailedTask() {
        String taskNo = insertTask(ExcelTaskStatus.FAILED, "任务超时，已判定为失败");

        int affected = excelTaskService.markSuccess(taskNo, 100, 100, 0);

        assertEquals(0, affected, "已经是失败终态的任务，成功终态写入必须被拒绝");
        ExcelTask after = reload(taskNo);
        assertEquals(ExcelTaskStatus.FAILED.getCode(), after.getStatus(),
                "状态不能被改回成功：否则用户被告知「请重新提交」、重提之后原任务又变成成功，同一批数据进两遍");
        assertEquals("任务超时，已判定为失败", after.getErrorMsg(), "原有失败原因不能被抹掉");
        assertEquals(0, after.getSuccessRows(), "被拒绝的写入不能留下任何痕迹");
    }

    @Test
    void markFailed_shouldNotOverwriteExistingFailureReason() {
        String taskNo = insertTask(ExcelTaskStatus.FAILED, "清理任务写入的原始失败原因");

        int affected = excelTaskService.markFailed(taskNo, "线程池执行异常");

        assertEquals(0, affected, "失败终态不可被二次覆盖");
        assertEquals("清理任务写入的原始失败原因", reload(taskNo).getErrorMsg(),
                "后到的失败原因不能覆盖先到的：先到的那个才是根因");
    }

    @Test
    void markRunning_shouldNotClaimTaskAlreadyMarkedFailed() {
        String taskNo = insertTask(ExcelTaskStatus.FAILED, "任务超时，已判定为失败");

        assertEquals(0, excelTaskService.markRunning(taskNo),
                "已被判为失败的任务不能被重新领走，否则会产出一份没人认领的结果");
        assertEquals(ExcelTaskStatus.FAILED.getCode(), reload(taskNo).getStatus());
    }

    @Test
    void updateProgress_shouldNotWriteToFinalizedTask() {
        String taskNo = insertTask(ExcelTaskStatus.FAILED, "任务超时，已判定为失败");

        assertEquals(0, excelTaskService.updateProgress(taskNo, 8000, 8000, 0),
                "终态任务不该再接收进度回写");
        ExcelTask after = reload(taskNo);
        assertEquals(0, after.getSuccessRows(),
                "否则任务详情会显示「状态=失败，但成功 8000 行」这种自相矛盾的组合");
    }

    @Test
    void markSuccess_shouldRejectPendingTask() {
        String taskNo = insertTask(ExcelTaskStatus.PENDING, null);

        assertEquals(0, excelTaskService.markSuccess(taskNo, 100, 100, 0),
                "只允许「执行中 → 成功」，不能从「待执行」直接跳到终态（跳过领取就等于跳过了 CAS 本身）");
        assertEquals(ExcelTaskStatus.PENDING.getCode(), reload(taskNo).getStatus());
    }

    // ---------------- 允许的状态流转必须照常生效 ----------------

    @Test
    void markFailed_shouldAcceptPendingTask() {
        // 受理阶段的两条失败路径（落盘失败、线程池队列已满）都是在任务还没开跑时写失败，
        // 如果 markFailed 也要求 status=1，这两条路径就会静默写不进去、任务永远停在「待执行」
        String taskNo = insertTask(ExcelTaskStatus.PENDING, null);

        assertEquals(1, excelTaskService.markFailed(taskNo, "任务排队已满，请稍后重新提交"));

        ExcelTask after = reload(taskNo);
        assertEquals(ExcelTaskStatus.FAILED.getCode(), after.getStatus());
        assertEquals("任务排队已满，请稍后重新提交", after.getErrorMsg());
    }

    @Test
    void happyPath_pendingToRunningToSuccess_shouldAllSucceed() {
        String taskNo = insertTask(ExcelTaskStatus.PENDING, null);

        assertEquals(1, excelTaskService.markRunning(taskNo));
        assertEquals(1, excelTaskService.updateProgress(taskNo, 50, 50, 0));
        assertEquals(1, excelTaskService.markSuccess(taskNo, 100, 100, 0));

        ExcelTask after = reload(taskNo);
        assertEquals(ExcelTaskStatus.SUCCESS.getCode(), after.getStatus());
        assertEquals(100, after.getProcessedRows());
        assertEquals(100, after.getSuccessRows());
        assertNotNull(after.getStartTime(), "markRunning 应当写入开始时间");
        assertNotNull(after.getEndTime(), "markSuccess 应当写入结束时间");
    }

    /**
     * 成功终态必须把 error_msg 真正清空
     *
     * <p>这条断言的意义在于「{@code wrapper.set(..., null)} 在真库上确实可执行且确实生效」：
     * 如果退回 {@code update.setErrorMsg(null)}，NOT_NULL 策略会把整个字段跳过，
     * 旧失败原因就会一直挂在一条「已完成」的任务上。
     *
     * <p>说明：当前执行链路里还没有「先写失败原因、再把同一任务跑成功」的路径
     * （失败即终态），所以这是对代码意图的防御性保证，而不是在修一个已发生的线上问题。
     * 之所以仍要用真库钉住，是因为一旦将来加了重跑/重试，这里就是数据正确性的最后一道闸。
     */
    @Test
    void markSuccess_shouldClearErrorMsgOnRealDatabase() {
        String taskNo = insertTask(ExcelTaskStatus.RUNNING, "上一轮的失败原因");

        assertEquals(1, excelTaskService.markSuccess(taskNo, 100, 98, 2));

        ExcelTask after = reload(taskNo);
        assertEquals(ExcelTaskStatus.SUCCESS.getCode(), after.getStatus());
        assertNull(after.getErrorMsg(),
                "成功终态必须把 error_msg 清空；留着旧失败原因会让用户以为这次也失败了");
    }

    // ---------------- 测试辅助 ----------------

    /**
     * 直接落一条指定状态的任务行
     *
     * <p>不走 {@code createImportTask / createExportTask}：那两个入口只会建出「待执行」的任务，
     * 而本类要覆盖的恰恰是「任务已经处于执行中 / 终态时，写方法还该不该生效」。
     */
    private String insertTask(ExcelTaskStatus status, String errorMsg) {
        ExcelTask task = new ExcelTask();
        task.setTaskNo("CAS" + System.nanoTime());
        task.setBizType("CAS_TEST");
        task.setBizName("CAS 语义测试");
        task.setTaskType(1);
        task.setStatus(status.getCode());
        task.setErrorMsg(errorMsg);
        task.setCreatedBy(ADMIN_ID);
        excelTaskMapper.insert(task);
        createdTaskNos.add(task.getTaskNo());
        return task.getTaskNo();
    }

    private ExcelTask reload(String taskNo) {
        ExcelTask task = excelTaskMapper.selectOne(
                Wrappers.<ExcelTask>lambdaQuery().eq(ExcelTask::getTaskNo, taskNo));
        assertNotNull(task, "任务应当存在：" + taskNo);
        return task;
    }
}
