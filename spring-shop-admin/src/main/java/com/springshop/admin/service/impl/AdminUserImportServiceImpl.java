package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.service.AdminUserImportService;
import com.springshop.admin.vo.AdminUserImportResultVO;
import com.springshop.common.excel.ExcelReadException;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.excel.ImportError;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员批量导入服务实现
 *
 * <p>模板为「一行一个管理员」的扁平结构，列顺序见 {@link #HEADERS}。
 * 校验全部在内存完成后逐行写入，配合「部分成功」策略：合法行落库，非法行只记错误不阻断其他行。
 */
@Service
public class AdminUserImportServiceImpl implements AdminUserImportService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserImportServiceImpl.class);

    /** 单次导入的管理员行数上限 */
    private static final int MAX_IMPORT_ROWS = 500;

    /** 密码最短长度，与 DTO 校验保持一致 */
    private static final int MIN_PASSWORD_LENGTH = 6;

    /** 模板表头，列顺序与下面的列下标常量一一对应 */
    private static final List<String> HEADERS = List.of(
            "用户名*", "姓名", "手机号", "角色编码*(多个用逗号分隔)", "状态(1启用/0禁用)", "初始密码(留空用默认密码)");

    /** 模板示例行 */
    private static final List<List<String>> SAMPLE_ROWS = List.of(
            List.of("operator01", "张运营", "13800000000", "OPERATOR", "1", ""));

    private static final int COL_USERNAME = 0;

    private static final int COL_REAL_NAME = 1;

    private static final int COL_PHONE = 2;

    private static final int COL_ROLE_CODES = 3;

    private static final int COL_STATUS = 4;

    private static final int COL_PASSWORD = 5;

    /** 多个角色编码之间的分隔符（中英文逗号、顿号、分号、空白） */
    private static final String ROLE_CODE_SEPARATOR = "[,，、;；\\s]+";

    private final AdminUserMapper adminUserMapper;
    private final AdminUserRoleMapper adminUserRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final String defaultPassword;

    public AdminUserImportServiceImpl(AdminUserMapper adminUserMapper,
                                      AdminUserRoleMapper adminUserRoleMapper,
                                      RoleMapper roleMapper,
                                      PasswordEncoder passwordEncoder,
                                      @Value("${admin.import.default-password:123456}") String defaultPassword) {
        this.adminUserMapper = adminUserMapper;
        this.adminUserRoleMapper = adminUserRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.defaultPassword = defaultPassword;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserImportResultVO importUsers(MultipartFile file) {
        List<ExcelRow> rows = readRows(file);

        // 预加载：角色编码 → id（只认启用中的角色）、已存在的用户名，避免逐行查库（N+1）
        Map<String, Long> roleIdByCode = loadEnabledRoleIds();
        Set<String> existingUsernames = loadExistingUsernames(rows);

        List<ImportError> errors = new ArrayList<>();
        Set<String> importedUsernames = new HashSet<>();
        int successCount = 0;

        for (ExcelRow row : rows) {
            try {
                String username = row.cell(COL_USERNAME);
                if (ExcelSupport.isBlankText(username)) {
                    throw new IllegalArgumentException("用户名不能为空");
                }
                if (existingUsernames.contains(username) || importedUsernames.contains(username)) {
                    throw new IllegalArgumentException("用户名「" + username + "」已存在");
                }

                List<Long> roleIds = resolveRoleIds(row.cell(COL_ROLE_CODES), roleIdByCode);
                Integer status = parseStatus(row.cell(COL_STATUS));
                String rawPassword = row.cell(COL_PASSWORD);
                if (ExcelSupport.isBlankText(rawPassword)) {
                    rawPassword = defaultPassword;
                } else if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
                    throw new IllegalArgumentException("初始密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
                }

                insertAdminUser(username, rawPassword, row.cell(COL_REAL_NAME), row.cell(COL_PHONE), status, roleIds);
                // 只有真正落库的用户名才算占用：前面校验失败的行不该影响后面同名的合法行
                importedUsernames.add(username);
                successCount++;
            } catch (IllegalArgumentException e) {
                errors.add(new ImportError(row.getRowNum(), e.getMessage()));
            }
        }

        AdminUserImportResultVO result = new AdminUserImportResultVO();
        result.setTotalRows(rows.size());
        result.setSuccessCount(successCount);
        result.setFailCount(errors.size());
        result.setErrors(errors);
        log.info("管理员导入完成：共 {} 行，成功 {} 行，失败 {} 行", rows.size(), successCount, errors.size());
        return result;
    }

    @Override
    public byte[] buildTemplate() {
        try {
            return ExcelSupport.write("管理员导入模板", HEADERS, SAMPLE_ROWS);
        } catch (IOException e) {
            // 生成模板失败属于系统级问题，交由全局异常处理兜底
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "模板生成失败，请稍后重试");
        }
    }

    /**
     * 读取上传文件并解析为数据行；文件级问题直接抛业务异常，不进入逐行校验
     */
    private List<ExcelRow> readRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), "请选择要导入的文件");
        }
        String fileName = file.getOriginalFilename();
        if (!hasExcelExtension(fileName)) {
            throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), "仅支持 .xlsx / .xls 格式");
        }
        try (InputStream in = file.getInputStream()) {
            return ExcelSupport.read(in, MAX_IMPORT_ROWS);
        } catch (ExcelReadException e) {
            throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), e.getMessage());
        } catch (IOException e) {
            log.warn("读取管理员导入文件失败", e);
            throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(),
                    "文件读取失败，请重新导出后重试");
        }
    }

    private boolean hasExcelExtension(String fileName) {
        if (ExcelSupport.isBlankText(fileName)) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    /**
     * 加载启用中的角色：编码 → id
     */
    private Map<String, Long> loadEnabledRoleIds() {
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().eq(Role::getStatus, 1))
                .stream().collect(Collectors.toMap(Role::getCode, Role::getId, (a, b) -> a));
    }

    /**
     * 一次性查出文件中已存在的用户名
     */
    private Set<String> loadExistingUsernames(List<ExcelRow> rows) {
        Set<String> usernames = rows.stream()
                .map(row -> row.cell(COL_USERNAME))
                .filter(name -> !ExcelSupport.isBlankText(name))
                .collect(Collectors.toSet());
        if (usernames.isEmpty()) {
            return Set.of();
        }
        return adminUserMapper.selectList(
                        Wrappers.<AdminUser>lambdaQuery().in(AdminUser::getUsername, usernames))
                .stream().map(AdminUser::getUsername).collect(Collectors.toSet());
    }

    /**
     * 解析角色编码串，任意一个编码无法匹配到启用角色即视为该行失败
     */
    private List<Long> resolveRoleIds(String text, Map<String, Long> roleIdByCode) {
        if (ExcelSupport.isBlankText(text)) {
            throw new IllegalArgumentException("角色编码不能为空");
        }
        List<Long> roleIds = new ArrayList<>();
        for (String code : text.split(ROLE_CODE_SEPARATOR)) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Long roleId = roleIdByCode.get(trimmed);
            if (roleId == null) {
                throw new IllegalArgumentException("角色编码「" + trimmed + "」不存在或已停用");
            }
            if (!roleIds.contains(roleId)) {
                roleIds.add(roleId);
            }
        }
        if (roleIds.isEmpty()) {
            throw new IllegalArgumentException("角色编码不能为空");
        }
        return roleIds;
    }

    private Integer parseStatus(String text) {
        if (ExcelSupport.isBlankText(text)) {
            return 1;
        }
        Integer status = ExcelSupport.parseInt(text);
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("状态只能是 1（启用）或 0（禁用）");
        }
        return status;
    }

    private void insertAdminUser(String username, String rawPassword, String realName, String phone,
                                 Integer status, List<Long> roleIds) {
        AdminUser adminUser = new AdminUser();
        adminUser.setUsername(username);
        // 与新增接口一致：密码 BCrypt 加密后落库
        adminUser.setPassword(passwordEncoder.encode(rawPassword));
        adminUser.setRealName(ExcelSupport.isBlankText(realName) ? null : realName);
        adminUser.setPhone(ExcelSupport.isBlankText(phone) ? null : phone);
        adminUser.setStatus(status);
        adminUserMapper.insert(adminUser);

        for (Long roleId : roleIds) {
            AdminUserRole relation = new AdminUserRole();
            relation.setAdminUserId(adminUser.getId());
            relation.setRoleId(roleId);
            adminUserRoleMapper.insert(relation);
        }
    }
}
