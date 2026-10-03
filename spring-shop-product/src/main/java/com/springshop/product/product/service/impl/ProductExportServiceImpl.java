package com.springshop.product.product.service.impl;

import com.springshop.common.excel.ExcelExportSupport;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.product.product.dto.ProductExportQuery;
import com.springshop.product.product.service.ProductExportService;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductExportRow;
import com.springshop.product.product.vo.ProductListVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 商品导出服务实现
 *
 * <p><b>大数据导出的核心是「边查边写」</b>：按 {@code excel.task.export-page-size} 分页拉取，
 * 每页转换成导出模型后立刻写进 Excel 写缓存，页面对象用完即弃。
 * 全程只有「一页数据 + Fesod 的百行写缓存」在内存里，
 * 十万行导出与一千行导出的内存占用基本相同。
 *
 * <p>复用 {@link ProductQueryService#adminExportPage} 而不是自己写 SQL，
 * 是为了保证「列表页看到的」和「导出的」口径完全一致（同样带分类名与最低价聚合）。
 */
@Service
public class ProductExportServiceImpl implements ProductExportService {

    private static final Logger log = LoggerFactory.getLogger(ProductExportServiceImpl.class);

    private static final String SHEET_NAME = "商品列表";

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ProductQueryService productQueryService;

    private final ExcelTaskExecutor excelTaskExecutor;

    public ProductExportServiceImpl(ProductQueryService productQueryService,
                                    ExcelTaskExecutor excelTaskExecutor) {
        this.productQueryService = productQueryService;
        this.excelTaskExecutor = excelTaskExecutor;
    }

    @Override
    public ExcelTaskVO submitExport(ProductExportQuery query, Long adminId) {
        ProductExportQuery safeQuery = query == null ? new ProductExportQuery() : query;
        String fileName = "商品列表-" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        return excelTaskExecutor.submitExport(BIZ_TYPE, BIZ_NAME, adminId, fileName, safeQuery,
                this::processExport);
    }

    private void processExport(ExcelTaskContext context) throws IOException {
        int exported = ExcelExportSupport.export(context, ProductExportQuery.class,
                ProductExportRow.class, SHEET_NAME,
                (query, current, pageSize) -> productQueryService.adminExportPage(query, current, pageSize)
                        .stream().map(ProductExportRow::from).toList());
        log.info("商品导出完成 taskNo={} 共 {} 行", context.getTaskNo(), exported);
    }
}
