package com.springshop.admin.service;

import com.springshop.admin.dto.OperationLogExportQuery;
import com.springshop.admin.dto.OperationLogPageQuery;
import com.springshop.admin.vo.OperationLogVO;
import com.springshop.common.result.PageResult;

import java.util.List;

/**
 * 操作日志查询服务
 *
 * <p>只提供查询：审计记录由 {@code @OperationLog} 切面写入，不允许通过接口修改或删除，
 * 否则审计就失去可信度。
 */
public interface OperationLogService {

    /** 分页查询操作日志 */
    PageResult<OperationLogVO> page(OperationLogPageQuery query);

    /**
     * 导出一页操作日志
     *
     * @param current  页码，从 1 开始
     * @param pageSize 每页条数
     */
    List<OperationLogVO> exportPage(OperationLogExportQuery query, long current, long pageSize);
}
