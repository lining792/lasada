package com.lazada.controller;

import com.lazada.crawler.LazadaCrawler;
import com.lazada.entity.LazadaProduct;
import com.lazada.manjaro.CategoryMatcher;
import com.lazada.manjaro.ManjaroClient;
import com.lazada.manjaro.ManjaroUploader;
import com.lazada.service.ProductService;
import com.lazada.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MigrationController {

    private final ProductService productService;
    private final CategoryService categoryService;
    
    @Value("${oxylabs.username:}")
    private String oxylabsUser;
    
    @Value("${oxylabs.password:}")
    private String oxylabsPass;
    
    @Value("${qwen.api-key:}")
    private String qwenApiKey;
    
    // 用户自定义设置（覆盖配置文件）
    private String customOxylabsUser = null;
    private String customOxylabsPass = null;
    private String customQwenKey = null;

    // 任务状态
    private volatile boolean taskRunning = false;
    private volatile int taskTotal = 0;
    private volatile int taskProcessed = 0;
    private volatile int taskSuccess = 0;
    private volatile int taskFailed = 0;
    private volatile int taskSkipped = 0;
    private volatile String taskCurrentUrl = "";
    
    // Manjaro客户端（共享登录状态）
    private ManjaroClient manjaroClient = new ManjaroClient();
    private ManjaroUploader manjaroUploader = null;
    private CategoryMatcher categoryMatcher = null;

    // ==================== 登录相关 ====================

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "qwenKey", customQwenKey != null ? customQwenKey : (qwenApiKey != null ? qwenApiKey : ""),
                "oxylabsUser", customOxylabsUser != null ? customOxylabsUser : (oxylabsUser != null ? oxylabsUser : ""),
                "oxylabsPass", customOxylabsPass != null ? customOxylabsPass : (oxylabsPass != null ? oxylabsPass : "")
            )
        ));
    }

    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, String> body) {
        customQwenKey = body.get("qwenKey");
        customOxylabsUser = body.get("oxylabsUser");
        customOxylabsPass = body.get("oxylabsPass");
        
        // 重新初始化分类匹配器
        if (customQwenKey != null && !customQwenKey.isEmpty()) {
            categoryMatcher = new CategoryMatcher(customQwenKey);
            if (manjaroUploader != null) {
                manjaroUploader.setCategoryMatcher(categoryMatcher);
            }
        }
        
        return ResponseEntity.ok(Map.of("success", true, "message", "设置已保存"));
    }

    @GetMapping("/captcha")
    public ResponseEntity<?> getCaptcha() {
        try {
            manjaroClient = new ManjaroClient();
            if (!manjaroClient.getLoginPage()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "获取登录页面失败"));
            }
            String captchaFile = manjaroClient.getCaptcha();
            if (captchaFile == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "获取验证码失败"));
            }
            
            // 读取验证码图片转base64
            byte[] imageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(captchaFile));
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "captchaBase64", "data:image/png;base64," + base64
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String captcha = body.get("captcha");
        
        boolean success = manjaroClient.login(username, password, captcha);
        
        if (success) {
            // 初始化uploader
            manjaroUploader = new ManjaroUploader(manjaroClient);
            // 初始化分类匹配器
            categoryMatcher = new CategoryMatcher(qwenApiKey);
            if (categoryMatcher.getCategoryCount() > 0) {
                manjaroUploader.setCategoryMatcher(categoryMatcher);
            }
        }
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "登录成功" : "登录失败"
        ));
    }

    @GetMapping("/login/status")
    public ResponseEntity<?> loginStatus() {
        boolean loggedIn = false;
        if (manjaroClient != null) {
            try {
                loggedIn = manjaroClient.loadCookies() && manjaroClient.checkLoginStatusOnce();
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("loggedIn", loggedIn));
    }

    // ==================== 分类相关 ====================

    @PostMapping("/categories/sync")
    public ResponseEntity<?> syncCategories() {
        int count = categoryService.importFromJson();
        if (count > 0 && categoryMatcher != null) {
            categoryMatcher.loadCategories();
        }
        return ResponseEntity.ok(Map.of(
            "success", count > 0,
            "count", count,
            "message", count > 0 ? "同步成功" : "同步失败"
        ));
    }

    @GetMapping("/categories/count")
    public ResponseEntity<?> getCategoryCount() {
        return ResponseEntity.ok(Map.of("count", categoryService.count()));
    }

    // ==================== 商品相关 ====================

    @GetMapping("/products")
    public ResponseEntity<?> getProducts(@RequestParam(defaultValue = "100") int limit) {
        var products = productService.findAll(limit);
        return ResponseEntity.ok(Map.of("success", true, "data", products, "total", products.size()));
    }

    @GetMapping("/products/stats")
    public ResponseEntity<?> getProductStats() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", productService.getStats()
        ));
    }

    // ==================== Excel任务 ====================

    @PostMapping("/task/upload")
    public ResponseEntity<?> uploadExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "8") int linkColumn,
            @RequestParam(defaultValue = "2") int startRow) {
        
        if (taskRunning) {
            return ResponseEntity.ok(Map.of("success", false, "message", "任务正在运行中"));
        }
        
        // 检查登录状态
        if (manjaroUploader == null) {
            // 尝试从cookies恢复
            manjaroClient = new ManjaroClient();
            if (manjaroClient.loadCookies()) {
                manjaroUploader = new ManjaroUploader(manjaroClient);
                categoryMatcher = new CategoryMatcher(qwenApiKey);
                if (categoryMatcher.getCategoryCount() > 0) {
                    manjaroUploader.setCategoryMatcher(categoryMatcher);
                }
            } else {
                return ResponseEntity.ok(Map.of("success", false, "message", "请先登录Manjaro"));
            }
        }
        
        // 解析Excel
        List<String> urls = new ArrayList<>();
        try {
            ZipSecureFile.setMinInflateRatio(0);
            Workbook workbook = new XSSFWorkbook(file.getInputStream());
            Sheet sheet = workbook.getSheetAt(0);
            
            for (int i = startRow - 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                Cell cell = row.getCell(linkColumn - 1);
                if (cell == null) continue;
                
                String url = "";
                if (cell.getCellType() == CellType.STRING) {
                    url = cell.getStringCellValue().trim();
                } else if (cell.getCellType() == CellType.NUMERIC) {
                    url = String.valueOf((long) cell.getNumericCellValue());
                }
                
                if (url.startsWith("http") && url.contains("lazada")) {
                    urls.add(url);
                }
            }
            workbook.close();
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "解析Excel失败: " + e.getMessage()));
        }
        
        if (urls.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "未找到有效的Lazada链接"));
        }
        
        // 启动异步任务
        startTask(urls);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "任务已启动",
            "total", urls.size()
        ));
    }

    private void startTask(List<String> urls) {
        taskRunning = true;
        taskTotal = urls.size();
        taskProcessed = 0;
        taskSuccess = 0;
        taskFailed = 0;
        taskSkipped = 0;
        
        // 使用自定义设置或默认配置
        String finalOxylabsUser = customOxylabsUser != null && !customOxylabsUser.isEmpty() ? customOxylabsUser : oxylabsUser;
        String finalOxylabsPass = customOxylabsPass != null && !customOxylabsPass.isEmpty() ? customOxylabsPass : oxylabsPass;
        
        new Thread(() -> {
            LazadaCrawler crawler = new LazadaCrawler(finalOxylabsUser, finalOxylabsPass);
            
            try {
                for (String url : urls) {
                    if (!taskRunning) break;
                    
                    taskCurrentUrl = url;
                    
                    // 检查是否已存在
                    if (productService.existsByUrl(url)) {
                        taskSkipped++;
                        taskProcessed++;
                        continue;
                    }
                    
                    try {
                        // 爬取
                        LazadaProduct product = crawler.crawlAndSave(url);
                        if (product == null || product.getTitle() == null) {
                            taskFailed++;
                            taskProcessed++;
                            continue;
                        }
                        
                        // 保存到数据库
                        product = productService.save(product);
                        
                        // 上传
                        var result = manjaroUploader.uploadProduct(product);
                        if (result.success) {
                            taskSuccess++;
                            productService.updateStatus(product.getId(), 2, null);
                            log.info("上传成功: {}", product.getTitle().substring(0, Math.min(30, product.getTitle().length())));
                        } else {
                            taskFailed++;
                            productService.updateStatus(product.getId(), -1, result.error);
                            log.warn("上传失败: {}", result.error);
                        }
                        
                        Thread.sleep(2000);
                        
                    } catch (Exception e) {
                        taskFailed++;
                        log.error("处理失败: {}", e.getMessage());
                    }
                    
                    taskProcessed++;
                }
            } finally {
                crawler.shutdown();
                taskRunning = false;
                taskCurrentUrl = "";
                log.info("任务完成: 成功{}, 失败{}, 跳过{}", taskSuccess, taskFailed, taskSkipped);
            }
        }).start();
    }

    @PostMapping("/task/stop")
    public ResponseEntity<?> stopTask() {
        taskRunning = false;
        return ResponseEntity.ok(Map.of("success", true, "message", "任务已停止"));
    }

    @GetMapping("/task/progress")
    public ResponseEntity<?> getTaskProgress() {
        return ResponseEntity.ok(Map.of(
            "running", taskRunning,
            "total", taskTotal,
            "processed", taskProcessed,
            "success", taskSuccess,
            "failed", taskFailed,
            "skipped", taskSkipped,
            "currentUrl", taskCurrentUrl
        ));
    }

    // ==================== 数据管理 ====================

    @DeleteMapping("/data/all")
    public ResponseEntity<?> clearAllData() {
        try {
            productService.deleteAll();
            categoryService.deleteAll();
            taskTotal = 0;
            taskProcessed = 0;
            taskSuccess = 0;
            taskFailed = 0;
            taskSkipped = 0;
            return ResponseEntity.ok(Map.of("success", true, "message", "数据已清空"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/data/products")
    public ResponseEntity<?> clearProducts() {
        try {
            productService.deleteAll();
            return ResponseEntity.ok(Map.of("success", true, "message", "商品已清空"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
