package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.service.AdminUserImportService;
import com.springshop.common.excel.ExcelFileType;
import com.springshop.common.excel.ExcelReadException;
import com.springshop.common.excel.ExcelReadOptions;
import com.springshop.common.excel.ExcelRow;
import com.springshop.common.excel.ExcelSupport;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskProperties;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员批量导入服务实现
 *
 * <p>模板为「一行一个管理员」的扁平结构，列顺序见 {@link #HEADERS}。
 *
 * <p><b>这个导入是真正流式的</b>：管理员模板没有「跨行聚合」的需求（不像商品要按名称合成 SPU），
 * 所以可以边读边攒批、批满即落库。峰值内存 = 一批（默认 500 行）+ Fesod 的解析缓冲，
 * 与文件总行数无关——这是它比商品导入更省内存的原因。
 *
 * <p><b>用户名查重也按批做</b>：不在开始时一次性把文件里所有用户名查一遍
 * （那需要先把全文件读进内存），而是每批插入前只查这一批的用户名。
 * 因为每批是独立提交的，后一批的查询天然能看到前一批已落库的用户名，
 * 跨批重名同样能被拦住，且查询次数从 1 次超大 IN 变成「批数」次小 IN。
 *
 * <p><b>BCrypt 是主要耗时</b>：默认密码只加密一次并复用（绝大多数行都留空用默认密码）；
 * 显式填写的密码按「批内去重」后逐个加密——重复密码不会重复付 BCrypt 的代价。
 */
@Service
public class AdminUserImportServiceImpl implements AdminUserImportService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserImportServiceImpl.class);

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

    /** 用户名查重的分片大小，避免单条 IN 超出数据库报文上限 */
    private static final int USERNAME_QUERY_CHUNK = 1000;

    private final AdminUserMapper adminUserMapper;
    private final AdminUserRoleMapper adminUserRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final ExcelTaskExecutor excelTaskExecutor;
    private final ExcelTaskProperties excelTaskProperties;
    private final TransactionTemplate transactionTemplate;
    private final String defaultPassword;

    public AdminUserImportServiceImpl(AdminUserMapper adminUserMapper,
                                      AdminUserRoleMapper adminUserRoleMapper,
                                      RoleMapper roleMapper,
                                      PasswordEncoder passwordEncoder,
                                      ExcelTaskExecutor excelTaskExecutor,
                                      ExcelTaskProperties excelTaskProperties,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${admin.import.default-password:123456}") String defaultPassword) {
        this.adminUserMapper = adminUserMapper;
        this.adminUserRoleMapper = adminUserRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.excelTaskExecutor = excelTaskExecutor;
        this.excelTaskProperties = excelTaskProperties;
        // 用 TransactionTemplate 而不是 @Transactional：执行体跑在异步线程上，
        // 而且这里需要「批内一个事务、批间独立」的精细控制
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.defaultPassword = defaultPassword;
    }

    @Override
    public ExcelTaskVO submitImport(MultipartFile file, Long adminId) {
        validateFile(file);
        return excelTaskExecutor.submitImport(BIZ_TYPE, BIZ_NAME, adminId, file, this::processImport);
    }

    @Override
    public byte[] buildTemplate() {
        try {
            return ExcelSupport.writeDynamic("管理员导入模板", HEADERS, SAMPLE_ROWS);
        } catch (IOException e) {
            // 生成模板失败属于系统级问题，交由全局异常处理兜底
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "模板生成失败，请稍后重试");
        }
    }

    /**
     * 后台线程执行体：流式解析 → 攒批 → 逐批校验落库
     *
     * <p>包级可见（非 private）是为了让单元测试能直接驱动执行体，
     * 不必真的起线程池、也不必等异步任务跑完再断言。
     */
    void processImport(ExcelTaskContext context) {
        Map<String, Long> roleIdByCode = loadEnabledRoleIds();
        // 默认密码只加密一次：绝大多数行都留空，逐行 BCrypt 是纯粹的浪费
        String encodedDefaultPassword = passwordEncoder.encode(defaultPassword);
        new Runner(context, roleIdByCode, encodedDefaultPassword).run();
    }

    /**
     * 单次导入的执行器
     *
     * <p>做成对象而不是用一堆局部变量 + lambda：状态（批次、计数）要在流式回调与落库方法间共享，
     * 用对象承载比用 {@code int[]} 之类的技巧可读得多，且天然是「一个任务一份」、线程安全。
     */
    private final class Runner {

        private final ExcelTaskContext context;

        private final Map<String, Long> roleIdByCode;

        private final String encodedDefaultPassword;

        private final int batchSize;

        private final List<RowCandidate> batch = new ArrayList<>();

        private int processedRows;

        private int successRows;

        private int failRows;

        private Runner(ExcelTaskContext context, Map<String, Long> roleIdByCode, String encodedDefaultPassword) {
            this.context = context;
            this.roleIdByCode = roleIdByCode;
            this.encodedDefaultPassword = encodedDefaultPassword;
            this.batchSize = excelTaskProperties.getImportBatchSize();
        }

        private void run() {
            ExcelReadOptions options = ExcelReadOptions.defaults()
                    .fileType(ExcelFileType.fromFileName(context.getFileName()))
                    .maxRows(excelTaskProperties.getMaxImportRows());
            try (InputStream in = context.openSource()) {
                ExcelSupport.streamRead(in, options, this::accept);
            } catch (ExcelReadException e) {
                throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), e.getMessage());
            } catch (IOException e) {
                log.warn("读取管理员导入文件失败 taskNo={}", context.getTaskNo(), e);
                throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(),
                        "文件读取失败，请重新导出后重试");
            }
            flush();
            log.info("管理员导入完成 taskNo={} 共 {} 行，成功 {} 行，失败 {} 行",
                    context.getTaskNo(), processedRows, successRows, failRows);
        }

        /**
         * 逐行回调：校验通过则进批，批满即落库
         */
        private void accept(ExcelRow row) {
            processedRows++;
            try {
                batch.add(parse(row));
            } catch (IllegalArgumentException e) {
                error(row.getRowNum(), e.getMessage());
            }
            if (batch.size() >= batchSize) {
                flush();
            }
            context.reportProgress(processedRows, successRows, failRows);
        }

        /**
         * 落一批：先查重、再加密、再入库
         */
        private void flush() {
            if (batch.isEmpty()) {
                return;
            }
            List<RowCandidate> current = List.copyOf(batch);
            batch.clear();

            Set<String> existing = loadExistingUsernames(
                    current.stream().map(RowCandidate::username).collect(Collectors.toSet()));
            Set<String> seenInBatch = new HashSet<>();
            List<RowCandidate> accepted = new ArrayList<>(current.size());
            for (RowCandidate candidate : current) {
                if (existing.contains(candidate.username()) || !seenInBatch.add(candidate.username())) {
                    error(candidate.rowNum(), "用户名「" + candidate.username() + "」已存在");
                    continue;
                }
                accepted.add(candidate);
            }
            if (!accepted.isEmpty()) {
                encodePasswords(accepted);
                persistWithFallback(accepted);
            }
            // 落库阶段才是「成功/失败」真正结算的地方（查重失败、唯一键冲突都发生在这里），
            // 所以批处理结束后必须再上报一次计数——否则调度器收尾时读到的是
            // 「上一行处理完时」的旧值，任务会以「成功 0 / 失败 0」收场
            context.reportProgress(processedRows, successRows, failRows);
        }

        /**
         * 批内一个事务；整批失败则降级为逐行重试，把失败精确落到那一行
         */
        private void persistWithFallback(List<RowCandidate> accepted) {
            try {
                transactionTemplate.executeWithoutResult(status -> persist(accepted));
                successRows += accepted.size();
                return;
            } catch (Exception e) {
                log.warn("管理员导入批落库失败，降级为逐行重试，批大小={}", accepted.size(), e);
            }
            // 整批回滚后逐行重试：能定位到具体是哪一行撞了唯一键，其余行仍然能导入成功
            for (RowCandidate candidate : accepted) {
                try {
                    transactionTemplate.executeWithoutResult(status -> persist(List.of(candidate)));
                    successRows++;
                } catch (Exception e) {
                    error(candidate.rowNum(), "落库失败：" + rootMessage(e));
                }
            }
        }

        /**
         * 批内去重后逐个加密显式密码，并给留空的行回填默认密码
         *
         * <p>同一批里出现多次相同密码（比如运营给一批临时账号设了同一个初始密码）时只加密一次。
         *
         * <p>注意不能因为「本批没有任何显式密码」就提前 return：那样留空的行会带着
         * {@code null} 密码去写库，把「用默认密码」变成「密码为空」。
         */
        private void encodePasswords(List<RowCandidate> accepted) {
            Set<String> rawPasswords = new LinkedHashSet<>();
            for (RowCandidate candidate : accepted) {
                if (candidate.rawPassword() != null) {
                    rawPasswords.add(candidate.rawPassword());
                }
            }
            Map<String, String> encoded = new HashMap<>(rawPasswords.size());
            for (String raw : rawPasswords) {
                encoded.put(raw, passwordEncoder.encode(raw));
            }
            for (RowCandidate candidate : accepted) {
                candidate.setEncodedPassword(candidate.rawPassword() == null
                        ? encodedDefaultPassword : encoded.get(candidate.rawPassword()));
            }
        }

        private void persist(List<RowCandidate> candidates) {
            List<AdminUser> users = new ArrayList<>(candidates.size());
            for (RowCandidate candidate : candidates) {
                AdminUser adminUser = new AdminUser();
                adminUser.setUsername(candidate.username());
                adminUser.setPassword(candidate.encodedPassword());
                adminUser.setRealName(candidate.realName());
                adminUser.setPhone(candidate.phone());
                adminUser.setStatus(candidate.status());
                users.add(adminUser);
            }
            adminUserMapper.insertBatch(users);

            List<AdminUserRole> relations = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                // insertBatch 会把自增主键回填到 users 上，据此补关联行的 adminUserId
                Long adminUserId = users.get(i).getId();
                for (Long roleId : candidates.get(i).roleIds()) {
                    AdminUserRole relation = new AdminUserRole();
                    relation.setAdminUserId(adminUserId);
                    relation.setRoleId(roleId);
                    relations.add(relation);
                }
            }
            adminUserRoleMapper.insertBatch(relations);
        }

        /**
         * 校验单行并构建候选对象；不合法时抛出 {@link IllegalArgumentException}，由调用方记录为该行的错误
         */
        private RowCandidate parse(ExcelRow row) {
            String username = row.cell(COL_USERNAME);
            if (ExcelSupport.isBlankText(username)) {
                throw new IllegalArgumentException("用户名不能为空");
            }
            List<Long> roleIds = resolveRoleIds(row.cell(COL_ROLE_CODES));
            Integer status = parseStatus(row.cell(COL_STATUS));
            String rawPassword = row.cell(COL_PASSWORD);
            if (!ExcelSupport.isBlankText(rawPassword) && rawPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("初始密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
            }
            String realName = row.cell(COL_REAL_NAME);
            String phone = row.cell(COL_PHONE);
            return new RowCandidate(row.getRowNum(), username,
                    ExcelSupport.isBlankText(rawPassword) ? null : rawPassword,
                    ExcelSupport.isBlankText(realName) ? null : realName,
                    ExcelSupport.isBlankText(phone) ? null : phone,
                    status, roleIds);
        }

        /**
         * 解析角色编码串，任意一个编码无法匹配到启用角色即视为该行失败
         */
        private List<Long> resolveRoleIds(String text) {
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

        private void error(int rowNum, String message) {
            context.addError(rowNum, message);
            failRows++;
        }
    }

    /**
     * 一次性查出给定用户名中已存在的（含逻辑删除行：用户名唯一索引不排除已删行）
     */
    private Set<String> loadExistingUsernames(Set<String> usernames) {
        if (usernames.isEmpty()) {
            return Set.of();
        }
        Set<String> existing = new HashSet<>();
        List<String> chunk = new ArrayList<>(USERNAME_QUERY_CHUNK);
        for (String username : usernames) {
            chunk.add(username);
            if (chunk.size() >= USERNAME_QUERY_CHUNK) {
                existing.addAll(queryUsernames(chunk));
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            existing.addAll(queryUsernames(chunk));
        }
        return existing;
    }

    private Set<String> queryUsernames(List<String> usernames) {
        return adminUserMapper.selectList(
                        Wrappers.<AdminUser>lambdaQuery().in(AdminUser::getUsername, usernames))
                .stream().map(AdminUser::getUsername).collect(Collectors.toSet());
    }

    /**
     * 加载启用中的角色：编码 → id
     */
    private Map<String, Long> loadEnabledRoleIds() {
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().eq(Role::getStatus, 1))
                .stream().collect(Collectors.toMap(Role::getCode, Role::getId, (a, b) -> a));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), "请选择要导入的文件");
        }
        if (ExcelFileType.fromFileName(file.getOriginalFilename()) == null) {
            throw new BusinessException(ResultCode.ADMIN_IMPORT_FILE_INVALID.getCode(), "仅支持 .xlsx / .xls 格式");
        }
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /**
     * 一行数据校验通过后的候选对象
     *
     * <p>密码字段分两步：{@code rawPassword} 是模板里的原文（null 表示用默认密码），
     * {@code encodedPassword} 在落库前批量加密后回填。分开是为了把「读表」与
     * 「付 BCrypt 代价」两步解耦，从而支持批内去重。
     */
    private static final class RowCandidate {

        private final int rowNum;

        private final String username;

        private final String rawPassword;

        private final String realName;

        private final String phone;

        private final Integer status;

        private final List<Long> roleIds;

        private String encodedPassword;

        private RowCandidate(int rowNum, String username, String rawPassword, String realName,
                             String phone, Integer status, List<Long> roleIds) {
            this.rowNum = rowNum;
            this.username = username;
            this.rawPassword = rawPassword;
            this.realName = realName;
            this.phone = phone;
            this.status = status;
            this.roleIds = roleIds;
        }

        private int rowNum() {
            return rowNum;
        }

        private String username() {
            return username;
        }

        private String rawPassword() {
            return rawPassword;
        }

        private String realName() {
            return realName;
        }

        private String phone() {
            return phone;
        }

        private Integer status() {
            return status;
        }

        private List<Long> roleIds() {
            return roleIds;
        }

        private String encodedPassword() {
            return encodedPassword;
        }

        private void setEncodedPassword(String encodedPassword) {
            this.encodedPassword = encodedPassword;
        }
    }
}
