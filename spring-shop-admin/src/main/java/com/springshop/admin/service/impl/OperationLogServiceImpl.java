package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.admin.dto.OperationLogPageQuery;
import com.springshop.admin.entity.OperationLog;
import com.springshop.admin.mapper.OperationLogMapper;
import com.springshop.admin.service.OperationLogService;
import com.springshop.admin.vo.OperationLogVO;
import com.springshop.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
                Wrappers.<OperationLog>lambdaQuery()
                        .eq(StringUtils.hasText(query.getModule()), OperationLog::getModule, query.getModule())
                        .like(StringUtils.hasText(query.getUsername()), OperationLog::getUsername, query.getUsername())
                        .like(StringUtils.hasText(query.getOperation()), OperationLog::getOperation, query.getOperation())
                        .eq(query.getStatus() != null, OperationLog::getStatus, query.getStatus())
                        .ge(query.getStartTime() != null, OperationLog::getCreateTime, query.getStartTime())
                        .le(query.getEndTime() != null, OperationLog::getCreateTime, query.getEndTime())
                        // 最新操作排前面：审计场景最关心「刚刚发生了什么」
                        .orderByDesc(OperationLog::getCreateTime)
                        .orderByDesc(OperationLog::getId));

        Page<OperationLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return PageResult.of(voPage);
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
