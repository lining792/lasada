package com.lazada.manjaro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazada.entity.LazadaProduct;
import com.lazada.entity.ManjaroCategory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Manjaro Supply 商品上传器
 */
public class ManjaroUploader {
    
    private final ManjaroClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CategoryMatcher categoryMatcher;
    
    // 默认分类（可配置）
    private String defaultCateId = "3871";
    private String defaultCateName = "Sports/Outdoors > Exercise & Fitness > Aquatic Fitness Equipment";
    
    public ManjaroUploader() {
        this.client = new ManjaroClient();
    }
    
    public ManjaroUploader(ManjaroClient client) {
        this.client = client;
    }
    
    /**
     * 初始化（加载cookies，检查登录状态）
     */
    public boolean init() {
        if (!client.loadCookies()) {
            System.err.println("请先登录Manjaro Supply");
            return false;
        }
        return client.checkLoginStatus();
    }
    
    /**
     * 设置分类匹配器
     */
    public void setCategoryMatcher(CategoryMatcher matcher) {
        this.categoryMatcher = matcher;
    }
    
    /**
     * 设置默认分类
     */
    public void setDefaultCategory(String cateId, String cateName) {
        this.defaultCateId = cateId;
        this.defaultCateName = cateName;
    }
    
    /**
     * 上传商品
     */
    public UploadResult uploadProduct(LazadaProduct product) {
        System.out.println("\n========== 开始上传商品 ==========");
        System.out.println("标题: " + product.getTitle());
        
        String tempDir = "./temp_images/" + product.getId();
        
        try {
            // 0. 匹配分类
            if (categoryMatcher != null && product.getCategoryName() != null) {
                ManjaroCategory matched = categoryMatcher.match(product.getCategoryName());
                if (matched != null) {
                    this.defaultCateId = matched.getCategoryId();
                    this.defaultCateName = matched.getFullPath();
                    System.out.println("匹配分类: " + matched.getFullPath());
                }
            }
            
            // 1. 下载并上传图片
            List<String> uploadedImages = downloadAndUploadImages(product);
            if (uploadedImages.isEmpty()) {
                return new UploadResult(false, null, "图片上传失败");
            }
            System.out.println("上传了 " + uploadedImages.size() + " 张图片");
            
            // 2. 获取运费模板
            List<Map<String, String>> transports = client.getTransportList();
            Map<String, String> transport = transports.isEmpty() ? null : transports.get(0);
            if (transport == null) {
                return new UploadResult(false, null, "获取运费模板失败");
            }
            
            // 3. 获取分类规格
            List<ManjaroClient.SpecInfo> specs = client.getCategorySpecs(defaultCateId);
            
            // 4. 处理规格映射
            Map<String, SpecMapping> specMappings = mapSpecifications(product, specs);
            
            // 5. 构建并提交表单
            String commonId = submitProduct(product, uploadedImages, transport, specs, specMappings);
            
            if (commonId != null) {
                System.out.println("商品上传成功! commonid=" + commonId);
                return new UploadResult(true, commonId, null);
            } else {
                return new UploadResult(false, null, "提交商品失败");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return new UploadResult(false, null, e.getMessage());
        } finally {
            // 清理临时图片
            cleanupTempImages(tempDir);
        }
    }
    
    /**
     * 清理临时图片目录
     */
    private void cleanupTempImages(String tempDir) {
        try {
            Path dir = Path.of(tempDir);
            if (Files.exists(dir)) {
                Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b)) // 先删文件再删目录
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception ignored) {}
                    });
                System.out.println("已清理临时图片: " + tempDir);
            }
        } catch (Exception e) {
            System.err.println("清理临时图片失败: " + e.getMessage());
        }
    }
    
    /**
     * 下载并上传图片
     */
    private List<String> downloadAndUploadImages(LazadaProduct product) {
        List<String> uploaded = new ArrayList<>();
        
        if (product.getImages() == null || product.getImages().isEmpty()) {
            return uploaded;
        }
        
        String[] imageUrls = product.getImages().split(",");
        String tempDir = "./temp_images/" + product.getId();
        
        for (int i = 0; i < Math.min(imageUrls.length, 10); i++) {
            String imageUrl = imageUrls[i].trim();
            if (imageUrl.isEmpty()) continue;
            
            System.out.println("下载图片 " + (i + 1) + ": " + imageUrl);
            String localPath = client.downloadImage(imageUrl, tempDir);
            
            if (localPath != null) {
                System.out.println("上传图片: " + localPath);
                String serverName = client.uploadImage(localPath);
                if (serverName != null) {
                    uploaded.add(serverName);
                    System.out.println("  -> " + serverName);
                }
            }
        }
        
        return uploaded;
    }
    
    /**
     * 映射规格
     */
    private Map<String, SpecMapping> mapSpecifications(LazadaProduct product, List<ManjaroClient.SpecInfo> specs) {
        Map<String, SpecMapping> mappings = new HashMap<>();
        
        // 解析Lazada规格
        Map<String, String> lazadaSpecs = new HashMap<>();
        if (product.getSpecifications() != null && !product.getSpecifications().isEmpty()) {
            try {
                JsonNode json = objectMapper.readTree(product.getSpecifications());
                Iterator<String> names = json.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    lazadaSpecs.put(name.toLowerCase(), json.get(name).asText());
                }
            } catch (Exception ignored) {}
        }
        
        // 映射到Manjaro规格
        for (ManjaroClient.SpecInfo spec : specs) {
            String specNameLower = spec.name.toLowerCase();
            String value = null;
            
            // 尝试匹配
            if (specNameLower.contains("color")) {
                value = lazadaSpecs.getOrDefault("color family", 
                        lazadaSpecs.getOrDefault("color", "Other"));
            } else if (specNameLower.contains("size")) {
                value = lazadaSpecs.getOrDefault("size", 
                        lazadaSpecs.getOrDefault("variation", "Standard"));
            }
            
            if (value == null) value = "Other";
            
            // 查找或创建规格值
            ManjaroClient.SpecValue specValue = spec.findValue(value);
            if (specValue == null) {
                // 需要添加新规格值
                System.out.println("添加规格值: " + spec.name + " = " + value);
                String newId = client.addSpecValue(defaultCateId, spec.id, value);
                if (newId != null) {
                    specValue = new ManjaroClient.SpecValue(newId, value);
                }
            }
            
            if (specValue != null) {
                mappings.put(spec.id, new SpecMapping(spec.id, spec.name, specValue.id, specValue.name));
            }
        }
        
        return mappings;
    }
    
    /**
     * 提交商品
     */
    private String submitProduct(LazadaProduct product, List<String> images,
            Map<String, String> transport, List<ManjaroClient.SpecInfo> specs,
            Map<String, SpecMapping> specMappings) throws Exception {
        
        // 构建表单数据
        List<String[]> formData = new ArrayList<>();
        
        // 基本字段
        formData.add(new String[]{"form_submit", "ok"});
        formData.add(new String[]{"commonid", ""});
        formData.add(new String[]{"type_id", "1"});
        formData.add(new String[]{"ref_url", "https://www.manjarosupply.com/shop/index.php?act=store_goods_add&op=index"});
        formData.add(new String[]{"cate_id", defaultCateId});
        formData.add(new String[]{"cate_name", defaultCateName});
        formData.add(new String[]{"g_name", product.getTitle()});
        
        // 价格处理
        String price = parsePrice(product.getPrice());
        String marketPrice = parsePrice(product.getOriginalPrice());
        if (marketPrice == null || marketPrice.equals("0")) marketPrice = price;
        
        formData.add(new String[]{"g_price", price});
        formData.add(new String[]{"g_marketprice", marketPrice});
        formData.add(new String[]{"g_costprice", "0.00"});
        formData.add(new String[]{"g_discount", ""});
        
        // 规格
        String warehouse = "JIT";
        StringBuilder skuKeyBuilder = new StringBuilder("i_");
        
        for (ManjaroClient.SpecInfo spec : specs) {
            SpecMapping mapping = specMappings.get(spec.id);
            if (mapping != null) {
                formData.add(new String[]{"sp_name[" + spec.id + "]", spec.name});
                formData.add(new String[]{"sp_val[" + spec.id + "][" + mapping.valueId + "]", mapping.valueName});
                skuKeyBuilder.append(mapping.valueId);
            }
        }
        skuKeyBuilder.append("_").append(warehouse);
        String skuKey = skuKeyBuilder.toString();
        
        formData.add(new String[]{"warehouse[]", warehouse});
        
        // SKU详情
        String colorValId = specMappings.containsKey("1") ? specMappings.get("1").valueId : "";
        formData.add(new String[]{"spec[" + skuKey + "][goods_id]", ""});
        formData.add(new String[]{"spec[" + skuKey + "][color]", colorValId});
        formData.add(new String[]{"spec[" + skuKey + "][country_id]", warehouse});
        formData.add(new String[]{"spec[" + skuKey + "][color]", colorValId});
        
        for (SpecMapping mapping : specMappings.values()) {
            formData.add(new String[]{"spec[" + skuKey + "][sp_value][" + mapping.valueId + "]", mapping.valueName});
        }
        
        formData.add(new String[]{"spec[" + skuKey + "][marketprice]", marketPrice});
        formData.add(new String[]{"spec[" + skuKey + "][price]", price});
        formData.add(new String[]{"spec[" + skuKey + "][stock]", "100"});
        formData.add(new String[]{"spec[" + skuKey + "][alarm]", "0"});
        formData.add(new String[]{"spec[" + skuKey + "][sku]", ""});
        formData.add(new String[]{"spec[" + skuKey + "][barcode]", ""});
        
        // 库存
        formData.add(new String[]{"g_storage", "100"});
        formData.add(new String[]{"g_alarm", ""});
        formData.add(new String[]{"g_serial", ""});
        formData.add(new String[]{"g_barcode", ""});
        
        // 图片（随机选5张）
        Random rand = new Random();
        String[] imageNames = new String[5];
        for (int i = 0; i < 5; i++) {
            imageNames[i] = images.isEmpty() ? "" : images.get(rand.nextInt(images.size()));
        }
        formData.add(new String[]{"image_path", imageNames[0]});
        formData.add(new String[]{"image_size_path", imageNames[1]});
        formData.add(new String[]{"image_scene_path", imageNames[2]});
        formData.add(new String[]{"image_detail_path", imageNames[3]});
        formData.add(new String[]{"image_detail_path_1", imageNames[4]});
        formData.add(new String[]{"video_path", ""});
        
        // 品牌
        formData.add(new String[]{"b_name", ""});
        formData.add(new String[]{"b_id", ""});
        formData.add(new String[]{"search_brand_keyword", ""});
        
        // 属性
        formData.add(new String[]{"material", extractMaterial(product)});
        formData.add(new String[]{"commodity", extractCommodity(product)});
        
        // 尺寸重量
        String[] dims = parseDimensions(product.getDimensions());
        formData.add(new String[]{"length", dims[0]});
        formData.add(new String[]{"width", dims[1]});
        formData.add(new String[]{"height", dims[2]});
        formData.add(new String[]{"weight", parseWeight(product.getWeight())});
        
        // 包装
        formData.add(new String[]{"package_num[]", "1"});
        formData.add(new String[]{"package_name[]", product.getPackingList() != null ? product.getPackingList() : "1"});
        
        // 描述（包含图片）
        StringBuilder descHtml = new StringBuilder();
        if (product.getDescription() != null) {
            descHtml.append(product.getDescription().substring(0, Math.min(500, product.getDescription().length())));
        }
        for (String img : images) {
            descHtml.append("<img src=\"https://image.manjarosupply.com/shop/store/goods/223/")
                    .append(img).append("@!product-1280\" />");
        }
        formData.add(new String[]{"g_body", descHtml.toString()});
        formData.add(new String[]{"jumpMenu", "0"});
        formData.add(new String[]{"m_body", ""});
        
        // 模板
        formData.add(new String[]{"plate_top", "请选择"});
        formData.add(new String[]{"plate_bottom", "请选择"});
        
        // 地区
        formData.add(new String[]{"region", ""});
        formData.add(new String[]{"province_id", ""});
        formData.add(new String[]{"city_id", ""});
        formData.add(new String[]{"area_id", ""});
        
        // 运费
        formData.add(new String[]{"freight", "1"});
        formData.add(new String[]{"transport_id", transport.get("id")});
        formData.add(new String[]{"transport_title", transport.get("name")});
        formData.add(new String[]{"express_type", transport.get("trans_type")});
        
        // 其他
        formData.add(new String[]{"g_vat", "0"});
        formData.add(new String[]{"sgcate_id[]", "0"});
        formData.add(new String[]{"g_state", "1"});
        formData.add(new String[]{"approve_dispatch_time", "48"});
        formData.add(new String[]{"g_commend", "1"});
        formData.add(new String[]{"sup_id", "0"});
        
        // 发送请求
        return sendMultipartForm(formData, images);
    }
    
    /**
     * 发送multipart表单（使用ManjaroClient的OkHttp客户端共享cookies）
     */
    private String sendMultipartForm(List<String[]> formData, List<String> images) throws Exception {
        String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        StringBuilder body = new StringBuilder();
        
        for (String[] field : formData) {
            body.append("--").append(boundary).append("\r\n");
            body.append("Content-Disposition: form-data; name=\"").append(field[0]).append("\"\r\n\r\n");
            body.append(field[1]).append("\r\n");
            
            // 插入空文件字段
            if (field[0].equals("image_path")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"goods_image\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else if (field[0].equals("image_size_path")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"goods_image_1\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else if (field[0].equals("image_scene_path")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"goods_image_2\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else if (field[0].equals("image_detail_path")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"goods_image_3\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else if (field[0].equals("image_detail_path_1")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"goods_image_4\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else if (field[0].equals("video_path")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"goods_video_4\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            } else if (field[0].equals("g_body")) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"add_album\"; filename=\"\"\r\n");
                body.append("Content-Type: application/octet-stream\r\n\r\n\r\n");
            }
        }
        body.append("--").append(boundary).append("--\r\n");
        
        // 使用ManjaroClient发送请求（共享cookies）
        String url = "https://www.manjarosupply.com/shop/index.php?act=store_goods_add&op=save_goods";
        String response = client.postMultipartForm(url, body.toString(), boundary);
        
        if (response == null) {
            System.err.println("请求失败");
            return null;
        }
        
        // 提取commonid
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("commonid=(\\d+)").matcher(response);
        if (m.find()) {
            return m.group(1);
        }
        m = java.util.regex.Pattern.compile("name=\"commonid\"\\s+value=\"(\\d+)\"").matcher(response);
        if (m.find()) {
            return m.group(1);
        }
        
        // 保存响应用于调试
        Files.writeString(Path.of("manjaro_response.html"), response);
        System.out.println("响应已保存到 manjaro_response.html");
        
        return null;
    }
    
    // 辅助方法
    private String parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) return "0";
        return priceStr.replaceAll("[^0-9.]", "");
    }
    
    private String[] parseDimensions(String dims) {
        if (dims == null || dims.isEmpty()) return new String[]{"10", "10", "10"};
        String[] parts = dims.replaceAll("[^0-9.x×X ]", "").split("[xX× ]+");
        String l = parts.length > 0 ? parts[0].trim() : "10";
        String w = parts.length > 1 ? parts[1].trim() : "10";
        String h = parts.length > 2 ? parts[2].trim() : "10";
        return new String[]{l.isEmpty() ? "10" : l, w.isEmpty() ? "10" : w, h.isEmpty() ? "10" : h};
    }
    
    private String parseWeight(String weight) {
        if (weight == null || weight.isEmpty()) return "1";
        String num = weight.replaceAll("[^0-9.]", "");
        if (num.isEmpty()) return "1";
        // 如果是克，转换为千克
        if (weight.toLowerCase().contains("g") && !weight.toLowerCase().contains("kg")) {
            try {
                double g = Double.parseDouble(num);
                return String.valueOf(g / 1000);
            } catch (Exception e) {
                return "1";
            }
        }
        return num;
    }
    
    private String extractMaterial(LazadaProduct product) {
        if (product.getSkuAttrs() != null && product.getSkuAttrs().containsKey("Material")) {
            return product.getSkuAttrs().get("Material");
        }
        return "Plastic";
    }
    
    private String extractCommodity(LazadaProduct product) {
        if (product.getCategoryName() != null) {
            return product.getCategoryName();
        }
        return "General";
    }
    
    // 内部类
    private static class SpecMapping {
        String specId;
        String specName;
        String valueId;
        String valueName;
        
        SpecMapping(String specId, String specName, String valueId, String valueName) {
            this.specId = specId;
            this.specName = specName;
            this.valueId = valueId;
            this.valueName = valueName;
        }
    }
    
    public static class UploadResult {
        public boolean success;
        public String commonId;
        public String error;
        
        public UploadResult(boolean success, String commonId, String error) {
            this.success = success;
            this.commonId = commonId;
            this.error = error;
        }
    }
}
