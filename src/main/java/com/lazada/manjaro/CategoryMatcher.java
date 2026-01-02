package com.lazada.manjaro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazada.entity.ManjaroCategory;
import okhttp3.*;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 分类匹配器 - 使用通义千问大模型匹配商品分类
 */
public class CategoryMatcher {
    
    private static final String QWEN_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private List<ManjaroCategory> leafCategories = new ArrayList<>();
    private Map<String, ManjaroCategory> categoryMap = new HashMap<>();
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    
    public CategoryMatcher(String apiKey) {
        this.apiKey = apiKey;
        loadCategories();
    }
    
    /**
     * 从JSON文件加载分类
     */
    public void loadCategories() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("manjaro_categories.json")) {
            if (is == null) {
                System.err.println("找不到 manjaro_categories.json");
                return;
            }
            
            JsonNode root = objectMapper.readTree(is);
            leafCategories.clear();
            categoryMap.clear();
            
            for (JsonNode node : root) {
                ManjaroCategory cat = new ManjaroCategory();
                cat.setCategoryId(node.get("id").asText());
                cat.setName(node.get("name").asText());
                cat.setFullPath(node.get("full_path").asText());
                cat.setLevel(3);
                leafCategories.add(cat);
                categoryMap.put(cat.getCategoryId(), cat);
            }
            
            System.out.println("已加载 " + leafCategories.size() + " 个分类");
        } catch (Exception e) {
            System.err.println("加载分类失败: " + e.getMessage());
        }
    }

    /**
     * 匹配分类 - 使用Lazada分类名称匹配Manjaro分类
     * @param lazadaCategoryName Lazada的分类名称
     * @return 匹配到的Manjaro分类，如果没匹配到返回null
     */
    public ManjaroCategory match(String lazadaCategoryName) {
        if (leafCategories.isEmpty()) {
            System.err.println("没有分类数据，请先加载分类");
            return null;
        }
        
        if (lazadaCategoryName == null || lazadaCategoryName.isEmpty()) {
            return null;
        }
        
        // 优先使用大模型匹配
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                ManjaroCategory result = matchByLLM(lazadaCategoryName);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                System.err.println("大模型匹配失败，使用关键词匹配: " + e.getMessage());
            }
        }
        
        // 备用：关键词匹配
        return matchByKeyword(lazadaCategoryName);
    }
    
    /**
     * 使用通义千问大模型匹配分类
     */
    private ManjaroCategory matchByLLM(String lazadaCategoryName) throws Exception {
        // 预筛选候选分类
        List<ManjaroCategory> candidates = preFilterCategories(lazadaCategoryName);
        if (candidates.isEmpty()) {
            candidates = leafCategories.subList(0, Math.min(100, leafCategories.size()));
        }
        
        // 构建分类选项（最多50个）
        StringBuilder categoryOptions = new StringBuilder();
        List<ManjaroCategory> finalCandidates = candidates.subList(0, Math.min(50, candidates.size()));
        for (int i = 0; i < finalCandidates.size(); i++) {
            ManjaroCategory cat = finalCandidates.get(i);
            categoryOptions.append(String.format("%d. [%s] %s\n", i + 1, cat.getCategoryId(), cat.getFullPath()));
        }
        
        // 构建prompt
        String prompt = String.format(
            "你是一个电商商品分类专家。请根据源分类名称，从下面的目标分类列表中选择最合适的一个分类。\n\n" +
            "源分类名称：%s\n\n" +
            "目标分类列表：\n%s\n" +
            "重要：你必须从上面的列表中选择一个最接近的分类，即使不是完全匹配也要选择最相关的。\n" +
            "只返回分类ID数字，不要返回任何其他内容。例如：3596",
            lazadaCategoryName,
            categoryOptions.toString()
        );
        
        // 调用API
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "qwen-turbo");
        
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);
        input.set("messages", messages);
        requestBody.set("input", input);
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("max_tokens", 20);
        parameters.put("temperature", 0.1f);
        requestBody.set("parameters", parameters);

        Request request = new Request.Builder()
                .url(QWEN_API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(requestBody), 
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                System.err.println("通义千问API错误: " + response.code() + " - " + respBody);
                return null;
            }

            JsonNode json = objectMapper.readTree(respBody);
            JsonNode output = json.get("output");
            if (output != null && output.has("text")) {
                String resultText = output.get("text").asText().trim();
                
                // 先尝试作为序号解析（1-50）
                try {
                    int index = Integer.parseInt(resultText.replaceAll("[^0-9]", ""));
                    if (index >= 1 && index <= finalCandidates.size()) {
                        ManjaroCategory cat = finalCandidates.get(index - 1);
                        System.out.println("大模型匹配: " + lazadaCategoryName + " -> " + cat.getFullPath());
                        return cat;
                    }
                } catch (NumberFormatException ignored) {}
                
                // 再尝试作为分类ID解析
                String categoryId = extractCategoryId(resultText);
                if (categoryId != null && categoryMap.containsKey(categoryId)) {
                    ManjaroCategory cat = categoryMap.get(categoryId);
                    System.out.println("大模型匹配: " + lazadaCategoryName + " -> " + cat.getFullPath());
                    return cat;
                }
            }
            
            System.err.println("大模型返回结果无法解析: " + respBody);
            return null;
        }
    }

    /**
     * 预筛选分类（基于关键词）
     */
    private List<ManjaroCategory> preFilterCategories(String categoryName) {
        String nameLower = categoryName.toLowerCase();
        List<ManjaroCategory> filtered = new ArrayList<>();
        
        // 提取关键词
        String[] words = nameLower.split("[\\s,./\\-_&>]+");
        Set<String> keywords = new HashSet<>();
        for (String word : words) {
            if (word.length() > 2) {
                keywords.add(word);
            }
        }
        
        // 筛选包含关键词的分类
        for (ManjaroCategory cat : leafCategories) {
            String pathLower = cat.getFullPath().toLowerCase();
            for (String keyword : keywords) {
                if (pathLower.contains(keyword)) {
                    filtered.add(cat);
                    break;
                }
            }
        }
        
        return filtered;
    }
    
    /**
     * 从返回文本中提取分类ID
     */
    private String extractCategoryId(String text) {
        // 尝试直接匹配数字
        String cleaned = text.replaceAll("[^0-9]", "");
        if (!cleaned.isEmpty() && categoryMap.containsKey(cleaned)) {
            return cleaned;
        }
        
        // 尝试匹配方括号中的内容
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[?(\\d+)\\]?");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (categoryMap.containsKey(id)) {
                return id;
            }
        }
        
        return null;
    }
    
    /**
     * 关键词匹配（备用方案）
     */
    private ManjaroCategory matchByKeyword(String categoryName) {
        String nameLower = categoryName.toLowerCase();
        
        String[] words = nameLower.split("[\\s,./\\-_&>]+");
        Set<String> keywords = new HashSet<>();
        for (String word : words) {
            if (word.length() > 2) {
                keywords.add(word);
            }
        }
        
        ManjaroCategory bestMatch = null;
        double bestScore = 0;
        
        for (ManjaroCategory cat : leafCategories) {
            double score = 0;
            String pathLower = cat.getFullPath().toLowerCase();
            String catNameLower = cat.getName().toLowerCase();
            
            for (String keyword : keywords) {
                if (catNameLower.equals(keyword)) score += 10.0;
                else if (catNameLower.contains(keyword)) score += 5.0;
                if (pathLower.contains(keyword)) score += 2.0;
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestMatch = cat;
            }
        }
        
        if (bestMatch != null) {
            System.out.println("关键词匹配: " + categoryName + " -> " + bestMatch.getFullPath());
        }
        
        return bestMatch;
    }
    
    /**
     * 获取分类数量
     */
    public int getCategoryCount() {
        return leafCategories.size();
    }
    
    /**
     * 根据ID获取分类
     */
    public ManjaroCategory getCategoryById(String categoryId) {
        return categoryMap.get(categoryId);
    }
}
