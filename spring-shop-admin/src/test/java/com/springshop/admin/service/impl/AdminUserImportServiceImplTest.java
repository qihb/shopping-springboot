package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.vo.AdminUserImportResultVO;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员批量导入服务单元测试
 *
 * <p>测试数据用 {@link ExcelSupport#write} 真实生成 xlsx，再喂给导入逻辑，
 * 覆盖「POI 解析 → 逐行校验 → 落库」的完整往返，而不是只测内存里的假数据。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserImportServiceImplTest {

    private static final List<String> HEADERS = List.of(
            "用户名*", "姓名", "手机号", "角色编码*", "状态(1启用/0禁用)", "初始密码");

    private static final String DEFAULT_PASSWORD = "123456";

    @Mock
    private AdminUserMapper adminUserMapper;

    @Mock
    private AdminUserRoleMapper adminUserRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminUserImportServiceImpl importService;

    @BeforeEach
    void setUp() {
        importService = new AdminUserImportServiceImpl(adminUserMapper, adminUserRoleMapper, roleMapper,
                passwordEncoder, DEFAULT_PASSWORD);
    }

    /**
     * 预热 MyBatis-Plus 的 Lambda 元数据（沿用 product / cart / order 模块单测的既有做法）
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
    void importUsers_should_keep_valid_rows_and_report_invalid_ones() throws IOException {
        stubEnabledAdminRole();
        stubNoExistingUsernames();
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
        stubInsertAssignsId();

        MultipartFile file = excelFile("管理员.xlsx", List.of(
                List.of("operator01", "张运营", "13800000000", "ADMIN", "1", ""),
                // 角色编码不存在 → 该行失败
                List.of("operator02", "李四", "", "NO_SUCH_ROLE", "1", ""),
                // 密码短于 6 位 → 该行失败
                List.of("operator03", "王五", "", "ADMIN", "1", "abc")));

        AdminUserImportResultVO result = importService.importUsers(file);

        assertEquals(3, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(2, result.getFailCount());
        // 行号与 Excel 界面一致：表头是第 1 行，所以第一条数据是第 2 行
        assertEquals(3, result.getErrors().get(0).getRowNum());
        assertTrue(result.getErrors().get(0).getMessage().contains("NO_SUCH_ROLE"));
        assertEquals(4, result.getErrors().get(1).getRowNum());
        assertTrue(result.getErrors().get(1).getMessage().contains("密码"));
    }

    @Test
    void importUsers_should_use_default_password_when_blank() throws IOException {
        stubEnabledAdminRole();
        stubNoExistingUsernames();
        when(passwordEncoder.encode(DEFAULT_PASSWORD)).thenReturn("ENCODED_DEFAULT");
        stubInsertAssignsId();

        MultipartFile file = excelFile("管理员.xlsx", List.of(
                List.of("operator01", "张运营", "", "ADMIN", "", "")));

        AdminUserImportResultVO result = importService.importUsers(file);

        assertEquals(1, result.getSuccessCount());
        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).insert(captor.capture());
        assertEquals("ENCODED_DEFAULT", captor.getValue().getPassword());
        // 状态留空默认启用
        assertEquals(1, captor.getValue().getStatus());
    }

    @Test
    void importUsers_should_bind_roles_from_codes() throws IOException {
        stubEnabledAdminRole();
        stubNoExistingUsernames();
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
        stubInsertAssignsId();

        MultipartFile file = excelFile("管理员.xlsx", List.of(
                List.of("operator01", "张运营", "", "ADMIN，ADMIN", "1", "")));

        importService.importUsers(file);

        // 重复编码只绑定一次
        verify(adminUserRoleMapper).insert(any(AdminUserRole.class));
    }

    @Test
    void importUsers_should_report_duplicate_username_inside_file() throws IOException {
        stubEnabledAdminRole();
        stubNoExistingUsernames();
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
        stubInsertAssignsId();

        MultipartFile file = excelFile("管理员.xlsx", List.of(
                List.of("operator01", "张运营", "", "ADMIN", "1", ""),
                List.of("operator01", "重复", "", "ADMIN", "1", "")));

        AdminUserImportResultVO result = importService.importUsers(file);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(3, result.getErrors().get(0).getRowNum());
        assertTrue(result.getErrors().get(0).getMessage().contains("已存在"));
    }

    @Test
    void importUsers_should_report_existing_username_from_db() throws IOException {
        AdminUser existing = new AdminUser();
        existing.setUsername("operator01");
        when(adminUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        MultipartFile file = excelFile("管理员.xlsx", List.of(
                List.of("operator01", "张运营", "", "ADMIN", "1", "")));

        AdminUserImportResultVO result = importService.importUsers(file);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        verify(adminUserMapper, never()).insert(any(AdminUser.class));
    }

    @Test
    void importUsers_should_reject_file_without_data_rows() throws IOException {
        byte[] onlyHeader = ExcelSupport.write("管理员导入模板", HEADERS, List.of());

        BusinessException ex = assertBusinessException(file("管理员.xlsx", onlyHeader));

        assertEquals(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("没有可导入的数据行"));
    }

    @Test
    void importUsers_should_reject_non_excel_extension() throws IOException {
        byte[] content = ExcelSupport.write("管理员导入模板", HEADERS,
                List.of(List.of("operator01", "张运营", "", "ADMIN", "1", "")));

        BusinessException ex = assertBusinessException(file("管理员.csv", content));

        assertEquals(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("xlsx"));
    }

    @Test
    void importUsers_should_reject_empty_file() {
        MultipartFile empty = new MockMultipartFile("file", "管理员.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        BusinessException ex = assertBusinessException(empty);

        assertEquals(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void importUsers_should_reject_unparsable_file() {
        MultipartFile broken = new MockMultipartFile("file", "管理员.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not an excel file".getBytes());

        BusinessException ex = assertBusinessException(broken);

        assertEquals(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void buildTemplate_should_produce_parseable_workbook() throws IOException {
        byte[] template = importService.buildTemplate();

        List<ExcelRow> rows = ExcelSupport.read(new ByteArrayInputStream(template), 10);

        // 模板必须自带一行示例，运营照着填即可
        assertEquals(1, rows.size());
        assertEquals("operator01", rows.get(0).cell(0));
    }

    private void stubEnabledAdminRole() {
        Role role = new Role();
        role.setId(1L);
        role.setCode("ADMIN");
        role.setStatus(1);
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(role));
    }

    private void stubNoExistingUsernames() {
        when(adminUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    private void stubInsertAssignsId() {
        when(adminUserMapper.insert(any(AdminUser.class))).thenAnswer(invocation -> {
            AdminUser user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        });
    }

    private MultipartFile excelFile(String fileName, List<List<String>> rows) throws IOException {
        return file(fileName, ExcelSupport.write("sheet", HEADERS, rows));
    }

    private MultipartFile file(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private BusinessException assertBusinessException(MultipartFile file) {
        return assertThrows(BusinessException.class, () -> importService.importUsers(file));
    }
}
