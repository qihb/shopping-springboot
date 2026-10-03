package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.common.excel.ExcelReadOptions;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.excel.task.ExcelFileStorage;
import com.springshop.common.excel.task.ExcelTask;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskError;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.ExcelTaskService;
import com.springshop.common.excel.task.ExcelTaskStatus;
import com.springshop.common.excel.task.ExcelTaskType;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员批量导入执行体单元测试
 *
 * <p>直接驱动 {@code processImport}（异步任务的执行体），覆盖「流式解析 → 攒批 → 逐批校验落库」。
 * 线程池调度本身由 web 模块的集成测试覆盖。
 *
 * <p>这个导入是**真正流式**的（没有跨行聚合），所以批次行为值得单独测：
 * 批大小设成 1 时应当每行落一次库。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserImportServiceImplTest {

    private static final String TASK_NO = "I-ADMIN-TEST";

    private static final String DEFAULT_PASSWORD = "123456";

    private static final List<String> HEADERS = List.of(
            "用户名*", "姓名", "手机号", "角色编码*(多个用逗号分隔)", "状态(1启用/0禁用)", "初始密码(留空用默认密码)");

    @TempDir
    Path tmpDir;

    @Mock
    private AdminUserMapper adminUserMapper;

    @Mock
    private AdminUserRoleMapper adminUserRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ExcelTaskExecutor excelTaskExecutor;

    @Mock
    private ExcelTaskService taskService;

    private ExcelTaskProperties properties;

    private AdminUserImportServiceImpl importService;

    /** 当前用例的任务上下文（由 runImport 赋值） */
    private ExcelTaskContext context;

    @BeforeEach
    void setUp() {
        properties = new ExcelTaskProperties();
        properties.setTmpDir(tmpDir.toString());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        // 密码编码用可预测的假实现，便于断言「到底加密了几次、存的哪个值」
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        importService = new AdminUserImportServiceImpl(adminUserMapper, adminUserRoleMapper, roleMapper,
                passwordEncoder, excelTaskExecutor, properties, transactionManager, DEFAULT_PASSWORD);
    }

    /**
     * 预热 MyBatis-Plus 的 Lambda 元数据：服务里的用户名查重与角色查询都用
     * {@code LambdaQueryWrapper}，缺少 TableInfo 会直接抛「can not find lambda cache for this entity」
     */
    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { AdminUser.class, AdminUserRole.class, Role.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @Test
    void importUsers_shouldInsertUsersAndBindRoles() throws IOException {
        stubRoles(role(10L, "ADMIN"), role(20L, "OPERATOR"));
        stubInsertAssignsId();

        runImport(List.of(
                row("operator01", "张运营", "13800000001", "ADMIN", "1", ""),
                row("operator02", "李运营", "13800000002", "OPERATOR", "0", "")));

        assertEquals(2, context.getProcessedRows());
        assertEquals(2, context.getSuccessRows());
        assertEquals(0, context.getFailRows());

        ArgumentCaptor<List<AdminUser>> userCaptor = userListCaptor();
        verify(adminUserMapper).insertBatch(userCaptor.capture());
        List<AdminUser> users = userCaptor.getValue();
        assertEquals(2, users.size());
        assertEquals("operator01", users.get(0).getUsername());
        assertEquals("张运营", users.get(0).getRealName());
        assertEquals(1, users.get(0).getStatus());
        assertEquals(0, users.get(1).getStatus(), "状态列填 0 应落成禁用");
        // 留空的行用默认密码，且默认密码只加密一次（BCrypt 是主要耗时）
        assertEquals("ENC(" + DEFAULT_PASSWORD + ")", users.get(0).getPassword());
        verify(passwordEncoder).encode(DEFAULT_PASSWORD);

        ArgumentCaptor<List<AdminUserRole>> relationCaptor = relationListCaptor();
        verify(adminUserRoleMapper).insertBatch(relationCaptor.capture());
        List<AdminUserRole> relations = relationCaptor.getValue();
        assertEquals(2, relations.size());
        assertTrue(relations.stream().allMatch(relation -> relation.getAdminUserId() != null),
                "关联行的 adminUserId 应由 insertBatch 回填的自增主键补齐");
        assertEquals(10L, relations.get(0).getRoleId());
        assertEquals(20L, relations.get(1).getRoleId());
    }

    @Test
    void importUsers_should_split_multiple_role_codes() throws IOException {
        stubRoles(role(10L, "ADMIN"), role(20L, "OPERATOR"));
        stubInsertAssignsId();

        runImport(List.of(row("operator03", "多角色", "", "ADMIN，OPERATOR", "1", "")));

        assertEquals(1, context.getSuccessRows());
        ArgumentCaptor<List<AdminUserRole>> relationCaptor = relationListCaptor();
        verify(adminUserRoleMapper).insertBatch(relationCaptor.capture());
        assertEquals(2, relationCaptor.getValue().size(), "中英文逗号分隔的两个角色都应绑定");
    }

    @Test
    void importUsers_should_report_unknown_role_code() throws IOException {
        stubRoles(role(10L, "ADMIN"));

        runImport(List.of(row("operator04", "错角色", "", "NO_SUCH_ROLE", "1", "")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("NO_SUCH_ROLE"));
        verify(adminUserMapper, never()).insertBatch(any());
    }

    @Test
    void importUsers_should_report_duplicate_username_inside_file() throws IOException {
        stubRoles(role(10L, "ADMIN"));
        stubInsertAssignsId();

        runImport(List.of(
                row("dupuser", "甲", "", "ADMIN", "1", ""),
                row("dupuser", "乙", "", "ADMIN", "1", "")));

        assertEquals(1, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("已存在"));
    }

    @Test
    void importUsers_should_report_username_occupied_in_db() throws IOException {
        stubRoles(role(10L, "ADMIN"));
        AdminUser existing = new AdminUser();
        existing.setUsername("takenuser");
        when(adminUserMapper.selectList(any())).thenReturn(List.of(existing));

        runImport(List.of(row("takenuser", "占用", "", "ADMIN", "1", "")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("已存在"));
        verify(adminUserMapper, never()).insertBatch(any());
    }

    @Test
    void importUsers_should_report_blank_username() throws IOException {
        stubRoles(role(10L, "ADMIN"));

        runImport(List.of(row("", "无用户名", "", "ADMIN", "1", "")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("用户名不能为空"));
    }

    @Test
    void importUsers_should_reject_too_short_explicit_password() throws IOException {
        stubRoles(role(10L, "ADMIN"));

        runImport(List.of(row("shortpwd", "短密码", "", "ADMIN", "1", "123")));

        assertEquals(0, context.getSuccessRows());
        assertEquals(1, context.getFailRows());
        assertTrue(errors().get(0).getMessage().contains("不能少于"));
        verify(passwordEncoder, never()).encode("123");
    }

    @Test
    void importUsers_should_encode_explicit_password_only_once_per_distinct_value() throws IOException {
        stubRoles(role(10L, "ADMIN"));
        stubInsertAssignsId();

        runImport(List.of(
                row("pwduser1", "甲", "", "ADMIN", "1", "abc123"),
                row("pwduser2", "乙", "", "ADMIN", "1", "abc123")));

        assertEquals(2, context.getSuccessRows());
        // 同一批里相同密码只付一次 BCrypt 代价
        verify(passwordEncoder, times(1)).encode("abc123");

        ArgumentCaptor<List<AdminUser>> userCaptor = userListCaptor();
        verify(adminUserMapper).insertBatch(userCaptor.capture());
        assertTrue(userCaptor.getValue().stream()
                .allMatch(user -> "ENC(abc123)".equals(user.getPassword())));
    }

    /**
     * 批大小设为 1：应当每行落一次库，验证「攒批阈值」真的生效
     * （流式导入的内存上限就靠这个阈值兜住）
     */
    @Test
    void importUsers_should_flush_every_batch_when_batch_size_reached() throws IOException {
        stubRoles(role(10L, "ADMIN"));
        stubInsertAssignsId();
        properties.setImportBatchSize(1);

        runImport(List.of(
                row("batchuser1", "甲", "", "ADMIN", "1", ""),
                row("batchuser2", "乙", "", "ADMIN", "1", "")));

        assertEquals(2, context.getSuccessRows());
        verify(adminUserMapper, times(2)).insertBatch(any());
    }

    @Test
    void importUsers_should_retry_row_by_row_after_batch_failure() throws IOException {
        stubRoles(role(10L, "ADMIN"));
        // 整批失败 → 逐行重试：第一行也失败、第二行成功
        doThrow(new RuntimeException("uk_username 冲突"))
                .doThrow(new RuntimeException("uk_username 冲突"))
                .doAnswer(invocation -> assignAdminUserIds(invocation.getArgument(0)))
                .when(adminUserMapper).insertBatch(any());

        runImport(List.of(
                row("retryuser1", "甲", "", "ADMIN", "1", ""),
                row("retryuser2", "乙", "", "ADMIN", "1", "")));

        assertEquals(1, context.getSuccessRows(), "重试成功的那一行应计入成功");
        assertEquals(1, context.getFailRows(), "失败的那一行应精确失败");
        assertTrue(errors().get(0).getMessage().contains("落库失败"));
    }

    @Test
    void submitImport_should_reject_empty_file() {
        MultipartFile empty = new MockMultipartFile("file", "管理员.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.submitImport(empty, 1L));

        assertEquals(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void submitImport_should_reject_non_excel_extension() throws IOException {
        byte[] content = ExcelSupport.writeDynamic("sheet", HEADERS,
                List.of(row("operator09", "甲", "", "ADMIN", "1", "")));
        MultipartFile file = new MockMultipartFile("file", "管理员.csv", "text/csv", content);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.submitImport(file, 1L));

        assertEquals(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("xlsx"));
    }

    @Test
    void submitImport_should_handOverToExecutorWithBizType() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "管理员.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ExcelSupport.writeDynamic("sheet", HEADERS, List.of(row("operator09", "甲", "", "ADMIN", "1", ""))));

        importService.submitImport(file, 3L);

        verify(excelTaskExecutor).submitImport(eq("ADMIN_USER_IMPORT"), eq("管理员导入"), eq(3L), eq(file), any());
    }

    @Test
    void buildTemplate_should_contain_sample_row() throws IOException {
        byte[] template = importService.buildTemplate();

        List<ExcelRow> rows = ExcelSupport.readAll(
                new ByteArrayInputStream(template), ExcelReadOptions.defaults());

        assertEquals(1, rows.size());
        assertEquals("operator01", rows.get(0).cell(0));
    }

    // ---------- 测试辅助 ----------

    private void runImport(List<List<String>> rows) throws IOException {
        byte[] content = ExcelSupport.writeDynamic("管理员导入模板", HEADERS, rows);
        Path source = tmpDir.resolve("admin-import-" + System.nanoTime() + ".xlsx");
        Files.write(source, content);

        ExcelTask task = new ExcelTask();
        task.setTaskNo(TASK_NO);
        task.setBizType("ADMIN_USER_IMPORT");
        task.setFileName("管理员.xlsx");
        task.setFilePath(source.toString());
        task.setTaskType(ExcelTaskType.IMPORT.getCode());
        task.setStatus(ExcelTaskStatus.RUNNING.getCode());
        task.setCreatedBy(1L);

        context = new ExcelTaskContext(task, properties, taskService,
                new ExcelFileStorage(properties), new ObjectMapper());
        importService.processImport(context);
        // 执行体只负责攒批，刷库由调度器收尾时做；测试里手动触发一次以观察失败明细
        context.flush();
    }

    @SuppressWarnings("unchecked")
    private List<ExcelTaskError> errors() {
        ArgumentCaptor<List<ExcelTaskError>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskService, atLeastOnce()).saveErrors(eq(TASK_NO), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<AdminUser>> userListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<AdminUserRole>> relationListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private void stubRoles(Role... roles) {
        when(roleMapper.selectList(any())).thenReturn(List.of(roles));
    }

    /**
     * 模拟 {@code insertBatch} 的自增主键回填（真实实现靠
     * {@code @Options(useGeneratedKeys = true)} 完成）
     */
    private void stubInsertAssignsId() {
        when(adminUserMapper.insertBatch(any()))
                .thenAnswer(invocation -> assignAdminUserIds(invocation.getArgument(0)));
        when(adminUserRoleMapper.insertBatch(any())).thenReturn(1);
    }

    private int assignAdminUserIds(List<AdminUser> users) {
        long nextId = 500L;
        for (AdminUser user : users) {
            user.setId(nextId++);
        }
        return users.size();
    }

    private Role role(Long id, String code) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        role.setStatus(1);
        return role;
    }

    private List<String> row(String username, String realName, String phone,
                             String roleCodes, String status, String password) {
        return List.of(username, realName, phone, roleCodes, status, password);
    }
}
