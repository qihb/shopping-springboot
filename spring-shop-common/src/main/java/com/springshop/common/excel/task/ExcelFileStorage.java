package com.springshop.common.excel.task;

import com.springshop.common.excel.ExcelFileType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Excel 临时文件存取
 *
 * <p><b>为什么导入要先把上传文件落盘</b>：异步化之后，HTTP 请求在返回 taskNo 时就结束了，
 * {@code MultipartFile} 依赖的临时文件会被容器回收，后台线程再读就是空流。
 * 因此必须在受理阶段把内容落到自己的目录里。
 *
 * <p><b>为什么文件名不用用户上传的名字</b>：用户文件名里可能带 {@code ../} 或平台保留字符，
 * 直接拼路径会造成目录穿越。这里统一用「任务号 + 从原文件名提取的扩展名」命名，
 * 原文件名只作为展示字段存进 {@code excel_task.file_name}。
 *
 * <p>目录按日期分子目录（{@code {tmpDir}/{yyyyMMdd}/}），清理任务可以整目录删除，
 * 不用逐文件比较时间戳。
 */
@Component
public class ExcelFileStorage {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileStorage.class);

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String DEFAULT_EXTENSION = "xlsx";

    private final ExcelTaskProperties properties;

    public ExcelFileStorage(ExcelTaskProperties properties) {
        this.properties = properties;
    }

    /**
     * 保存上传的导入文件
     *
     * @param taskNo       任务号，作为落盘文件名
     * @param originalName 用户上传的原始文件名，仅用于推断扩展名
     */
    public Path saveImportFile(String taskNo, String originalName, MultipartFile file) throws IOException {
        Path target = targetPath(taskNo, extensionOf(originalName));
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * 创建导出结果文件路径（只建目录与路径，文件由写出器创建）
     */
    public Path createExportFile(String taskNo) throws IOException {
        Path target = targetPath(taskNo, DEFAULT_EXTENSION);
        Files.deleteIfExists(target);
        return target;
    }

    public boolean exists(String storedPath) {
        return storedPath != null && Files.isRegularFile(Path.of(storedPath));
    }

    /**
     * 删除文件，失败只告警：清理是尽力而为，不该因为一个删不掉的文件让整个任务报错
     */
    public void deleteQuietly(String storedPath) {
        if (storedPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(storedPath));
        } catch (IOException | RuntimeException e) {
            log.warn("删除 Excel 临时文件失败 path={}", storedPath, e);
        }
    }

    /**
     * 删除超过保留期的日期目录
     *
     * @return 删除的文件数
     */
    public int cleanupExpired() {
        Path root = properties.tmpDirPath();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        LocalDate deadline = LocalDate.now().minusDays(Math.max(1, properties.getFileRetainHours() / 24 + 1));
        int deleted = 0;
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (!isExpired(dir.getFileName().toString(), deadline)) {
                    continue;
                }
                try (var files = Files.list(dir)) {
                    for (Path file : files.toList()) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                }
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            log.warn("清理 Excel 临时目录失败 dir={}", root, e);
        }
        return deleted;
    }

    private boolean isExpired(String dateDirName, LocalDate deadline) {
        try {
            return LocalDate.parse(dateDirName, DATE_DIR).isBefore(deadline);
        } catch (RuntimeException e) {
            // 不是日期目录（可能是人工放进去的东西），不碰
            return false;
        }
    }

    private Path targetPath(String taskNo, String extension) throws IOException {
        Path dir = properties.tmpDirPath().resolve(LocalDate.now().format(DATE_DIR));
        Files.createDirectories(dir);
        return dir.resolve(taskNo + "." + extension);
    }

    private String extensionOf(String originalName) {
        ExcelFileType type = ExcelFileType.fromFileName(originalName);
        return type == ExcelFileType.XLS ? "xls" : DEFAULT_EXTENSION;
    }
}
