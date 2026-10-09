package com.springshop.order.service.impl;

import com.springshop.common.excel.ExcelExportSupport;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.order.dto.OrderExportQuery;
import com.springshop.order.service.OrderExportService;
import com.springshop.order.service.OrderService;
import com.springshop.order.vo.OrderExportRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 订单导出服务实现
 *
 * <p><b>大数据导出的核心是「边查边写」</b>：按 {@code excel.task.export-page-size} 分页拉取，
 * 每页转换成导出模型后立刻写进 Excel 写缓存，页面对象用完即弃。
 * 全程只有「一页数据 + Fesod 的百行写缓存」在内存里。
 *
 * <p>订单每页还要顺带查一次明细（{@code loadItems}），同样是「一页订单的明细」这个量级，
 * 不随总量增长，所以十万行订单导出不会把明细全捞进内存。
 */
@Service
public class OrderExportServiceImpl implements OrderExportService {

    private static final Logger log = LoggerFactory.getLogger(OrderExportServiceImpl.class);

    private static final String SHEET_NAME = "订单列表";

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderService orderService;

    private final ExcelTaskExecutor excelTaskExecutor;

    public OrderExportServiceImpl(OrderService orderService,
                                  ExcelTaskExecutor excelTaskExecutor) {
        this.orderService = orderService;
        this.excelTaskExecutor = excelTaskExecutor;
    }

    @Override
    public ExcelTaskVO submitExport(OrderExportQuery query, Long adminId) {
        OrderExportQuery safeQuery = query == null ? new OrderExportQuery() : query;
        String fileName = "订单列表-" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        return excelTaskExecutor.submitExport(BIZ_TYPE, BIZ_NAME, adminId, fileName, safeQuery,
                this::processExport);
    }

    private void processExport(ExcelTaskContext context) throws IOException {
        int exported = ExcelExportSupport.export(context, OrderExportQuery.class,
                OrderExportRow.class, SHEET_NAME,
                OrderExportRow::getId,
                (query, lastId, pageSize) -> orderService.exportPage(query, lastId, pageSize)
                        .stream().map(OrderExportRow::from).toList());
        log.info("订单导出完成 taskNo={} 共 {} 行", context.getTaskNo(), exported);
    }
}
