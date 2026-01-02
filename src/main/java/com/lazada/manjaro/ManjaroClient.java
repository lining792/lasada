package com.lazada.manjaro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazada.entity.ManjaroCategory;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.awt.Desktop;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manjaro Supply 客户端 - 登录、上传图片、上传商品
 */
public class ManjaroClient {
    
    private static final String BASE_URL = "https://www.manjarosupply.com/shop";
    private static final String COOKIE_FILE = "../manjaro_cookies.json";
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> cookies = new HashMap<>();
    
    // 登录相关
    private String formhash;
    private String nchash;
    
    public ManjaroClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookieList) {
                        for (Cookie cookie : cookieList) {
                            cookies.put(cookie.name(), cookie.value());
                        }
                    }
                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        List<Cookie> list = new ArrayList<>();
                        for (Map.Entry<String, String> entry : cookies.entrySet()) {
                            list.add(new Cookie.Builder()
                                    .domain(url.host())
                                    .name(entry.getKey())
                                    .value(entry.getValue())
                                    .build());
                        }
                        return list;
                    }
                })
                .build();
    }
    
    // ==================== 登录相关 ====================
    
    /**
     * 获取登录页面，提取formhash和nchash
     */
    public boolean getLoginPage() {
        String url = BASE_URL + "/index.php?act=seller_login";
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return false;
                
                String body = response.body() != null ? response.body().string() : "";
                Document doc = Jsoup.parse(body);
                
                // 提取formhash
                Element formhashInput = doc.selectFirst("input[name=formhash]");
                if (formhashInput != null) {
                    formhash = formhashInput.attr("value");
                }
                
                // 提取nchash
                Element nchashInput = doc.selectFirst("input[name=nchash]");
                if (nchashInput != null) {
                    nchash = nchashInput.attr("value");
                }
                
                System.out.println("formhash: " + formhash);
                System.out.println("nchash: " + nchash);
                System.out.println("PHPSESSID: " + cookies.get("PHPSESSID"));
                
                return formhash != null && nchash != null;
            }
        } catch (Exception e) {
            System.err.println("获取登录页面失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取验证码图片，保存到文件并返回路径
     */
    public String getCaptcha() {
        if (nchash == null) {
            System.err.println("请先获取登录页面");
            return null;
        }
        
        String url = BASE_URL + "/index.php?act=seccode&op=makecode&nchash=" + nchash;
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                
                byte[] imageData = response.body().bytes();
                String captchaFile = "manjaro_captcha.png";
                Files.write(Path.of(captchaFile), imageData);
                
                System.out.println("验证码已保存到 " + captchaFile + " (" + imageData.length + " bytes)");
                
                // 尝试打开图片
                try {
                    Desktop.getDesktop().open(new File(captchaFile));
                } catch (Exception ignored) {}
                
                return captchaFile;
            }
        } catch (Exception e) {
            System.err.println("获取验证码失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 校验验证码
     */
    public boolean checkCaptcha(String captcha) {
        if (nchash == null) return false;
        
        String url = BASE_URL + "/index.php?act=seccode&op=check&nchash=" + nchash + "&captcha=" + captcha;
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", BASE_URL + "/index.php?act=seller_login")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                System.out.println("验证码校验响应: " + body);
                return body.toLowerCase().contains("true") || body.contains("\"1\"") || body.trim().equals("1");
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 登录
     */
    public boolean login(String username, String password, String captcha) {
        if (formhash == null || nchash == null) {
            System.err.println("请先获取登录页面");
            return false;
        }
        
        // 先校验验证码
        if (!checkCaptcha(captcha)) {
            System.err.println("验证码校验失败");
            return false;
        }
        
        String url = BASE_URL + "/index.php?act=seller_login&op=login";
        
        try {
            FormBody body = new FormBody.Builder()
                    .add("formhash", formhash)
                    .add("nchash", nchash)
                    .add("form_submit", "ok")
                    .add("seller_name", username)
                    .add("password", password)
                    .add("captcha", captcha)
                    .build();
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "https://www.manjarosupply.com")
                    .header("Referer", BASE_URL + "/index.php?act=seller_login")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                System.out.println("登录响应状态码: " + response.code());
                String location = response.header("Location", "");
                System.out.println("响应头Location: " + location);
                
                // 登录成功通常会302重定向到卖家后台
                if (response.code() == 302) {
                    if (location.contains("seller_center") || location.contains("seller")) {
                        System.out.println("登录成功!");
                        System.out.println("当前Cookies: " + cookies);
                        saveCookies(username);
                        return true;
                    }
                }
                
                String respBody = response.body() != null ? response.body().string() : "";
                if (respBody.contains("登录成功") || respBody.contains("seller_center")) {
                    System.out.println("登录成功!");
                    saveCookies(username);
                    return true;
                }
                
                System.err.println("登录失败");
                Files.writeString(Path.of("manjaro_login_response.html"), respBody);
                return false;
            }
        } catch (Exception e) {
            System.err.println("登录异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存Cookies到文件和数据库
     */
    public void saveCookies() {
        saveCookies(null);
    }
    
    public void saveCookies(String username) {
        // 保存到文件
        try {
            objectMapper.writeValue(new File(COOKIE_FILE), cookies);
            System.out.println("Cookies已保存到 " + COOKIE_FILE);
        } catch (Exception e) {
            System.err.println("保存Cookies到文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 加载保存的Cookies（从文件）
     */
    public boolean loadCookies() {
        // 从文件加载
        File file = new File(COOKIE_FILE);
        if (!file.exists()) {
            System.out.println("Cookie文件不存在: " + COOKIE_FILE);
            return false;
        }
        
        try {
            JsonNode json = objectMapper.readTree(file);
            Iterator<String> names = json.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                cookies.put(name, json.get(name).asText());
            }
            System.out.println("已从文件加载Cookies: " + cookies.keySet());
            return true;
        } catch (IOException e) {
            System.err.println("加载Cookies失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查登录状态（无限重试直到成功，服务器有时会随机返回302）
     */
    public boolean checkLoginStatus() {
        String url = BASE_URL + "/index.php?act=seller_center";
        
        System.out.println("当前Cookies: " + cookies);
        
        int retryCount = 0;
        while (true) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    
                    if (response.code() == 200 && !body.contains("seller_login")) {
                        System.out.println("登录状态有效" + (retryCount > 0 ? " (重试了 " + retryCount + " 次)" : ""));
                        return true;
                    }
                }
                
                retryCount++;
                if (retryCount % 10 == 0) {
                    System.out.println("登录状态检查中... 已重试 " + retryCount + " 次");
                }
                Thread.sleep(300);
            } catch (Exception e) {
                retryCount++;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
        }
    }
    
    /**
     * 检查登录状态（单次检查，不重试）
     */
    public boolean checkLoginStatusOnce() {
        String url = BASE_URL + "/index.php?act=seller_center";
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                return response.code() == 200 && !body.contains("seller_login");
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取运费模板列表（无限重试）
     */
    public List<Map<String, String>> getTransportList() {
        String url = BASE_URL + "/index.php?act=store_transport&type=select";
        
        while (true) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    
                    if (body.length() < 1000 || !body.contains("data-param")) {
                        System.out.println("获取运费模板失败，重试...");
                        Thread.sleep(500);
                        continue;
                    }
                    
                    Document doc = Jsoup.parse(body);
                    List<Map<String, String>> templates = new ArrayList<>();
                    
                    for (Element a : doc.select("a[data-param]")) {
                        String dataParam = a.attr("data-param");
                        // 转换 {name:'1'} -> {"name":"1"}
                        dataParam = dataParam.replaceAll("(\\w+):'([^']*)'", "\"$1\":\"$2\"");
                        try {
                            JsonNode json = objectMapper.readTree(dataParam);
                            Map<String, String> template = new HashMap<>();
                            template.put("id", json.has("id") ? json.get("id").asText() : "");
                            template.put("name", json.has("name") ? json.get("name").asText() : "");
                            template.put("trans_type", json.has("trans_type") ? json.get("trans_type").asText() : "");
                            templates.add(template);
                        } catch (Exception ignored) {}
                    }
                    
                    System.out.println("获取到 " + templates.size() + " 个运费模板");
                    return templates;
                }
            } catch (Exception e) {
                System.out.println("获取运费模板异常，重试...");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
    }
    
    /**
     * 获取分类规格（无限重试）
     */
    public List<SpecInfo> getCategorySpecs(String cateId) {
        String url = BASE_URL + "/index.php?act=store_goods_add&op=add_step_two&class_id=" + cateId + "&t_id=";
        
        while (true) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    
                    if (body.length() < 10000 || !body.contains("spec_group_dl")) {
                        System.out.println("登录状态可能失效，重试...");
                        Thread.sleep(500);
                        continue;
                    }
                    
                    Document doc = Jsoup.parse(body);
                    List<SpecInfo> specs = new ArrayList<>();
                    
                    for (Element dl : doc.select("dl[nctype=spec_group_dl]")) {
                        Element specInput = dl.selectFirst("input[nctype=spec_name]");
                        if (specInput == null) continue;
                        
                        String specName = specInput.attr("value");
                        String dataParam = specInput.attr("data-param");
                        
                        // 解析 spec_id
                        String specId = "";
                        Matcher m = Pattern.compile("id:(\\d+)").matcher(dataParam);
                        if (m.find()) specId = m.group(1);
                        
                        // 获取规格值
                        List<SpecValue> values = new ArrayList<>();
                        for (Element checkbox : dl.select("input[type=checkbox]")) {
                            String nameAttr = checkbox.attr("name");
                            Matcher vm = Pattern.compile("sp_val\\[\\d+\\]\\[(\\d+)\\]").matcher(nameAttr);
                            if (vm.find()) {
                                String valId = vm.group(1);
                                String valName = checkbox.attr("value");
                                if (!valId.isEmpty() && !valName.isEmpty()) {
                                    values.add(new SpecValue(valId, valName));
                                }
                            }
                        }
                        
                        if (!specName.isEmpty()) {
                            specs.add(new SpecInfo(specId, specName, values));
                        }
                    }
                    
                    System.out.println("分类 " + cateId + " 的规格:");
                    for (SpecInfo spec : specs) {
                        System.out.println("  - " + spec.name + " (ID: " + spec.id + "): " + spec.values.size() + " 个值");
                    }
                    
                    return specs;
                }
            } catch (Exception e) {
                System.out.println("获取分类规格异常，重试...");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
    }
    
    /**
     * 上传图片
     */
    public String uploadImage(String imagePath) {
        String url = BASE_URL + "/index.php?act=store_goods_add&op=image_upload&upload_type=uploadedfile";
        
        try {
            File file = new File(imagePath);
            if (!file.exists()) {
                System.err.println("图片文件不存在: " + imagePath);
                return null;
            }
            
            RequestBody fileBody = RequestBody.create(
                    Files.readAllBytes(file.toPath()),
                    MediaType.parse("image/jpeg")
            );
            
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", "goods_image")
                    .addFormDataPart("goods_image", file.getName(), fileBody)
                    .build();
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", "https://www.manjarosupply.com")
                    .header("Referer", BASE_URL + "/index.php?act=store_goods_add&op=add_step_two")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                System.out.println("上传响应: " + respBody);
                
                JsonNode json = objectMapper.readTree(respBody);
                if (json.has("name")) {
                    return json.get("name").asText();
                }
            }
        } catch (Exception e) {
            System.err.println("上传图片失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 下载图片到本地
     */
    public String downloadImage(String imageUrl, String saveDir) {
        try {
            // 创建目录
            Path dir = Path.of(saveDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            
            // 生成文件名
            String fileName = UUID.randomUUID().toString().replace("-", "") + ".jpg";
            Path filePath = dir.resolve(fileName);
            
            Request request = new Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    Files.write(filePath, response.body().bytes());
                    return filePath.toString();
                }
            }
        } catch (Exception e) {
            System.err.println("下载图片失败: " + imageUrl + " - " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 发送multipart表单请求（用于商品上传）
     */
    public String postMultipartForm(String url, String body, String boundary) {
        try {
            RequestBody requestBody = RequestBody.create(
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MediaType.parse("multipart/form-data; boundary=" + boundary)
            );
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Origin", "https://www.manjarosupply.com")
                    .header("Referer", BASE_URL + "/index.php?act=store_goods_add&op=add_step_two")
                    .post(requestBody)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                System.out.println("响应状态码: " + response.code());
                
                // 处理302重定向
                if (response.code() == 302) {
                    String location = response.header("Location", "");
                    System.out.println("重定向到: " + location);
                    
                    // 如果重定向URL包含commonid，说明上传成功
                    if (location.contains("commonid=")) {
                        return location;
                    }
                    
                    // 跟随重定向获取最终页面
                    if (!location.isEmpty()) {
                        String redirectUrl = location.startsWith("http") ? location : BASE_URL + "/" + location;
                        Request redirectRequest = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .build();
                        try (Response redirectResponse = httpClient.newCall(redirectRequest).execute()) {
                            return redirectResponse.body() != null ? redirectResponse.body().string() : "";
                        }
                    }
                }
                
                return response.body() != null ? response.body().string() : "";
            }
        } catch (Exception e) {
            System.err.println("发送multipart表单失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 添加规格值
     */
    public String addSpecValue(String cateId, String specId, String valueName) {
        try {
            String url = BASE_URL + "/index.php?act=store_goods_add&op=ajax_add_spec&gc_id=" + cateId 
                    + "&sp_id=" + specId + "&name=" + java.net.URLEncoder.encode(valueName, "UTF-8");
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", BASE_URL + "/index.php?act=store_goods_add&op=add_step_two&class_id=" + cateId)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                System.out.println("添加规格值响应: " + respBody);
                
                // 尝试解析返回的ID
                if (respBody.trim().matches("\\d+")) {
                    return respBody.trim();
                }
                JsonNode json = objectMapper.readTree(respBody);
                if (json.has("value_id")) return json.get("value_id").asText();
                if (json.has("spv_id")) return json.get("spv_id").asText();
                if (json.has("id")) return json.get("id").asText();
            }
        } catch (Exception e) {
            System.err.println("添加规格值失败: " + e.getMessage());
        }
        return null;
    }
    
    // 内部类
    public static class SpecInfo {
        public String id;
        public String name;
        public List<SpecValue> values;
        
        public SpecInfo(String id, String name, List<SpecValue> values) {
            this.id = id;
            this.name = name;
            this.values = values;
        }
        
        public SpecValue findValue(String valueName) {
            for (SpecValue v : values) {
                if (v.name.equals(valueName)) return v;
            }
            return null;
        }
    }
    
    public static class SpecValue {
        public String id;
        public String name;
        
        public SpecValue(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
    
    /**
     * 获取所有分类列表
     */
    public List<ManjaroCategory> getAllCategories() {
        List<ManjaroCategory> categories = new ArrayList<>();
        String url = BASE_URL + "/index.php?act=store_goods_add&op=index";
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                
                // 调试：保存页面内容
                java.nio.file.Files.writeString(java.nio.file.Path.of("manjaro_category_page.html"), body);
                System.out.println("页面已保存到 manjaro_category_page.html，长度: " + body.length());
                
                // 尝试多种正则匹配分类数据
                String[] patterns = {
                    "var\\s+class_data\\s*=\\s*(\\[.*?\\]);",
                    "class_data\\s*=\\s*(\\[.*?\\]);",
                    "\"gc_id\".*?\"gc_name\""
                };
                
                for (String p : patterns) {
                    Pattern pattern = Pattern.compile(p, Pattern.DOTALL);
                    Matcher matcher = pattern.matcher(body);
                    if (matcher.find()) {
                        System.out.println("匹配到模式: " + p);
                        if (p.contains("\\[")) {
                            String jsonStr = matcher.group(1);
                            System.out.println("JSON长度: " + jsonStr.length());
                            System.out.println("JSON前200字符: " + jsonStr.substring(0, Math.min(200, jsonStr.length())));
                            JsonNode rootArray = objectMapper.readTree(jsonStr);
                            parseCategories(rootArray, categories, "", null, 1);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("获取分类列表失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("获取到 " + categories.size() + " 个分类");
        return categories;
    }
    
    /**
     * 递归解析分类
     */
    private void parseCategories(JsonNode node, List<ManjaroCategory> categories, 
            String parentPath, String parentId, int level) {
        if (node == null || !node.isArray()) return;
        
        for (JsonNode item : node) {
            String id = item.has("gc_id") ? item.get("gc_id").asText() : "";
            String name = item.has("gc_name") ? item.get("gc_name").asText() : "";
            
            if (id.isEmpty() || name.isEmpty()) continue;
            
            String fullPath = parentPath.isEmpty() ? name : parentPath + " > " + name;
            
            ManjaroCategory cat = new ManjaroCategory(id, name, fullPath, parentId, level);
            categories.add(cat);
            
            // 递归处理子分类
            if (item.has("child") && item.get("child").isArray()) {
                parseCategories(item.get("child"), categories, fullPath, id, level + 1);
            }
        }
    }
}
