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
     * @param lastId   keyset 游标：上一页最后一条的 id，{@code null} 表示从第一页开始。
     *                 按 id 倒序取 {@code id < lastId}。不能用 OFFSET 页码——
     *                 导出要跑几分钟，而审计日志一直在写，窗口会持续漂移，
     *                 导致已导出的日志重复、边缘的日志被整页跳过
     * @param pageSize 每页条数
     */
    List<OperationLogVO> exportPage(OperationLogExportQuery query, Long lastId, long pageSize);
}
