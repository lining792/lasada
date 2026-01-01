package com.lazada.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Oxylabs API客户端
 */
public class OxylabsClient {
    
    private static final String API_URL = "https://realtime.oxylabs.io/v1/queries";
    
    private final String username;
    private final String password;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public OxylabsClient(String username, String password) {
        this.username = username;
        this.password = password;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 根据URL自动识别地区
     */
    private String getGeoLocation(String url) {
        if (url.contains("lazada.co.th")) return "Thailand";
        if (url.contains("lazada.com.ph")) return "Philippines";
        if (url.contains("lazada.vn")) return "Vietnam";
        if (url.contains("lazada.com.my")) return "Malaysia";
        if (url.contains("lazada.sg")) return "Singapore";
        if (url.contains("lazada.co.id")) return "Indonesia";
        return "Thailand";
    }
    
    /**
     * 抓取Lazada页面HTML
     */
    public String fetchHtml(String url) throws IOException {
        String geo = getGeoLocation(url);
        System.out.println("检测到地区: " + geo);
        
        // 构建请求体
        String payload = objectMapper.writeValueAsString(new java.util.HashMap<String, Object>() {{
            put("source", "universal");
            put("url", url);
            put("render", "html");
            put("geo_location", geo);
            put("browser_instructions", new Object[]{
                new java.util.HashMap<String, Object>() {{
                    put("type", "wait");
                    put("wait_time_s", 5);
                }}
            });
        }});
        
        // Basic Auth
        String credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, MediaType.parse("application/json")))
                .build();
        
        System.out.println("正在抓取: " + url);
        System.out.println("请稍等，可能需要 10-30 秒...");
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: " + response.code() + " " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            
            if (root.has("results") && root.get("results").size() > 0) {
                String html = root.get("results").get(0).get("content").asText();
                System.out.println("✓ HTML获取成功，长度: " + html.length());
                return html;
            } else {
                throw new IOException("没有获取到内容");
            }
        }
    }
    
    /**
     * 关闭HTTP客户端
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
