package com.springshop.admin.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.admin.mapper.OperationLogMapper;
import com.springshop.common.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 操作日志切面：记录管理后台关键操作
 *
 * <p>通过 {@code @Around} 织入带 {@link com.springshop.admin.aspect.OperationLog} 注解的方法，
 * 记录操作人、请求信息、耗时与执行结果；审计日志写入失败不影响主流程。
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    /**
     * 需要脱敏的字段：字段名包含 password 一律替换（覆盖 password / oldPassword / newPassword）
     *
     * <p>只匹配 {@code "password"} 是不够的——{@code AdminChangePasswordRequest} 的字段名是
     * {@code oldPassword} / {@code newPassword}，用精确匹配会把明文密码写进审计日志。
     */
    private static final Pattern SENSITIVE_FIELD_PATTERN =
            Pattern.compile("\"([^\"]*password[^\"]*)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogMapper operationLogMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, com.springshop.admin.aspect.OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable error = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            saveLog(joinPoint, operationLog, System.currentTimeMillis() - start, error);
        }
    }

    private void saveLog(ProceedingJoinPoint joinPoint, com.springshop.admin.aspect.OperationLog operationLog,
                         long durationMs, Throwable error) {
        try {
            com.springshop.admin.entity.OperationLog entity = new com.springshop.admin.entity.OperationLog();
            entity.setAdminUserId(UserContext.getUserId());
            entity.setUsername(currentUsername());
            entity.setModule(operationLog.module());
            entity.setOperation(operationLog.operation());
            fillRequestInfo(entity);
            entity.setRequestParams(maskParams(joinPoint.getArgs()));
            entity.setDurationMs(durationMs);
            if (error == null) {
                entity.setStatus(1);
            } else {
                entity.setStatus(0);
                entity.setErrorMsg(error.getMessage() == null ? error.getClass().getSimpleName()
                        : truncate(error.getMessage(), 500));
            }
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计日志失败不应影响主流程
            log.warn("操作日志写入失败", e);
        }
    }

    private void fillRequestInfo(com.springshop.admin.entity.OperationLog entity) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            entity.setRequestUri(request.getRequestURI());
            entity.setRequestMethod(request.getMethod());
            entity.setIp(request.getRemoteAddr());
        }
    }

    /**
     * 请求参数序列化并脱敏（密码等敏感字段替换为 ***）
     */
    private String maskParams(Object[] args) {
        try {
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null || isFrameworkType(arg)) {
                    continue;
                }
                params.put("arg" + i, arg);
            }
            String json = objectMapper.writeValueAsString(params);
            // 保留字段名、只把值替换为 ***，便于排查问题时看出「改了哪些字段」
            json = SENSITIVE_FIELD_PATTERN.matcher(json).replaceAll("\"$1\":\"***\"");
            return truncate(json, 2000);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 排除 Servlet / 文件上传等框架类型参数：它们无法（也不需要）被 JSON 序列化
     */
    private boolean isFrameworkType(Object arg) {
        return arg instanceof HttpServletRequest
                || arg instanceof jakarta.servlet.http.HttpServletResponse
                || arg instanceof org.springframework.web.multipart.MultipartFile;
    }

    private String currentUsername() {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() == null ? null
                : org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        if (principal instanceof com.springshop.admin.security.AdminUserPrincipal adminPrincipal) {
            return adminPrincipal.getUsername();
        }
        return null;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
