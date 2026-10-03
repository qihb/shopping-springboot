package com.springshop.admin.service;

import com.springshop.common.excel.task.ExcelTaskVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理员批量导入服务
 */
public interface AdminUserImportService {

    /** 业务类型编码：用于任务台账区分与「同类任务去重」 */
    String BIZ_TYPE = "ADMIN_USER_IMPORT";

    /** 业务类型展示名 */
    String BIZ_NAME = "管理员导入";

    /**
     * 受理管理员批量导入
     *
     * <p>部分成功策略：逐行校验，合法行写入，非法行记录原因并可在任务中心下载失败明细。
     *
     * <p><b>接口语义是「已受理」而不是「已导入」</b>：模板允许填写初始密码，
     * 而密码走 BCrypt 加密（单次 50~100ms，是刻意的慢），上千行就要跑分钟级，
     * 同步接口必然超时。因此改为「落盘 + 建任务 + 立即返回任务号」，
     * 前端凭任务号轮询进度。
     *
     * @param file    上传的 xls / xlsx 文件
     * @param adminId 提交人（管理员 id）
     * @return 任务信息（含 taskNo）
     */
    ExcelTaskVO submitImport(MultipartFile file, Long adminId);

    /** 生成导入模板（xlsx 字节内容） */
    byte[] buildTemplate();
}
