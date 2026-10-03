package com.springshop.admin.service;

import com.springshop.admin.dto.OperationLogPageQuery;
import com.springshop.admin.vo.OperationLogVO;
import com.springshop.common.result.PageResult;

/**
 * 操作日志查询服务
 *
 * <p>只提供查询：审计记录由 {@code @OperationLog} 切面写入，不允许通过接口修改或删除，
 * 否则审计就失去可信度。
 */
public interface OperationLogService {

    /** 分页查询操作日志 */
    PageResult<OperationLogVO> page(OperationLogPageQuery query);
}
