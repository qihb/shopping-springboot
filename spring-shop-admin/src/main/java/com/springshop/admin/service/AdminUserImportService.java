package com.springshop.admin.service;

import com.springshop.admin.vo.AdminUserImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理员批量导入服务
 */
public interface AdminUserImportService {

    /**
     * 从 Excel 批量导入管理员账号
     *
     * <p>部分成功策略：逐行校验，合法行写入，非法行跳过并在结果里给出原因。
     *
     * @param file 上传的 xls / xlsx 文件
     * @return 导入结果（含逐行错误明细）
     */
    AdminUserImportResultVO importUsers(MultipartFile file);

    /** 生成导入模板（xlsx 字节内容） */
    byte[] buildTemplate();
}
