package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.admin.dto.OperationLogExportQuery;
import com.springshop.admin.dto.OperationLogPageQuery;
import com.springshop.admin.entity.OperationLog;
import com.springshop.admin.mapper.OperationLogMapper;
import com.springshop.admin.service.OperationLogService;
import com.springshop.admin.vo.OperationLogVO;
import com.springshop.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 操作日志查询服务实现
 */
@Service
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public PageResult<OperationLogVO> page(OperationLogPageQuery query) {
        Page<OperationLog> page = operationLogMapper.selectPage(query.toPage(),
                buildWrapper(query.getModule(), query.getUsername(), query.getOperation(),
                        query.getStatus(), query.getStartTime(), query.getEndTime()));

        Page<OperationLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return PageResult.of(voPage);
    }

    @Override
    public List<OperationLogVO> exportPage(OperationLogExportQuery query, long current, long pageSize) {
        // searchCount=false：导出不展示总页数，省掉每页一次 COUNT
        Page<OperationLog> page = operationLogMapper.selectPage(new Page<>(current, pageSize, false),
                buildWrapper(query.getModule(), query.getUsername(), query.getOperation(),
                        query.getStatus(), query.getStartTime(), query.getEndTime()));
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    private LambdaQueryWrapper<OperationLog> buildWrapper(String module, String username, String operation,
                                                         Integer status, LocalDateTime startTime,
                                                         LocalDateTime endTime) {
        return Wrappers.<OperationLog>lambdaQuery()
                .eq(StringUtils.hasText(module), OperationLog::getModule, module)
                .like(StringUtils.hasText(username), OperationLog::getUsername, username)
                .like(StringUtils.hasText(operation), OperationLog::getOperation, operation)
                .eq(status != null, OperationLog::getStatus, status)
                .ge(startTime != null, OperationLog::getCreateTime, startTime)
                .le(endTime != null, OperationLog::getCreateTime, endTime)
                // 最新操作排前面：审计场景最关心「刚刚发生了什么」
                .orderByDesc(OperationLog::getCreateTime)
                .orderByDesc(OperationLog::getId);
    }

    private OperationLogVO toVO(OperationLog log) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(log.getId());
        vo.setAdminUserId(log.getAdminUserId());
        vo.setUsername(log.getUsername());
        vo.setModule(log.getModule());
        vo.setOperation(log.getOperation());
        vo.setRequestUri(log.getRequestUri());
        vo.setRequestMethod(log.getRequestMethod());
        vo.setRequestParams(log.getRequestParams());
        vo.setIp(log.getIp());
        vo.setStatus(log.getStatus());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setDurationMs(log.getDurationMs());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
