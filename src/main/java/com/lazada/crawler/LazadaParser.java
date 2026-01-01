package com.lazada.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazada.entity.LazadaProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lazada HTML解析器
 */
public class LazadaParser {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 解析HTML提取商品信息
     */
    public LazadaProduct parse(String html) {
        Document doc = Jsoup.parse(html);
        LazadaProduct product = new LazadaProduct();
        
        // 1. 标题
        Element titleElem = doc.selectFirst("h1.pdp-mod-product-badge-title");
        if (titleElem == null) {
            titleElem = doc.selectFirst("title");
        }
        if (titleElem != null) {
            String title = titleElem.text().trim().replace(" | Lazada PH", "");
            product.setTitle(title);
        }
        
        // 2. 价格
        Element salePriceElem = doc.selectFirst(".pdp-v2-product-price-content-salePrice-amount");
        Element origPriceElem = doc.selectFirst(".pdp-v2-product-price-content-originalPrice-amount");
        Element discountElem = doc.selectFirst(".pdp-v2-product-price-content-originalPrice-discount");
        
        if (salePriceElem != null) product.setPrice(salePriceElem.text());
        if (origPriceElem != null) product.setOriginalPrice(origPriceElem.text());
        if (discountElem != null) product.setDiscount(discountElem.text());
        
        // 3. SKU属性
        Map<String, String> skuAttrs = new LinkedHashMap<>();
        Elements keyItems = doc.select(".key-li");
        for (Element item : keyItems) {
            Element keyElem = item.selectFirst(".key-title");
            Element valElem = item.selectFirst(".key-value");
            if (keyElem != null && valElem != null) {
                skuAttrs.put(keyElem.text().trim(), valElem.text().trim());
            }
        }
        product.setSkuAttrs(skuAttrs);
        
        // 4. 尺寸
        String width = skuAttrs.getOrDefault("Width", "");
        String height = skuAttrs.getOrDefault("Height", "");
        String length = skuAttrs.getOrDefault("Length", "");
        if (!width.isEmpty() || !height.isEmpty() || !length.isEmpty()) {
            product.setDimensions(length + " x " + width + " x " + height);
        }
        
        // 5. 重量
        product.setWeight(skuAttrs.get("Weight"));
        
        // 6. 图片
        Pattern imgPattern = Pattern.compile("//ph-test-11\\.slatic\\.net/p/[a-f0-9]+\\.jpg");
        Matcher imgMatcher = imgPattern.matcher(html);
        Set<String> images = new LinkedHashSet<>();
        while (imgMatcher.find()) {
            images.add("https:" + imgMatcher.group());
        }
        product.setImages(String.join(",", images));
        
        // 7. 描述和包装清单
        Element descElem = doc.selectFirst(".pdp-product-desc");
        if (descElem == null) descElem = doc.selectFirst(".detail-content");
        if (descElem == null) descElem = doc.selectFirst("#module_product_detail");
        
        if (descElem != null) {
            String descText = descElem.text();
            product.setDescription(descText);
            
            // 提取包装清单
            Pattern packingPattern = Pattern.compile("Package include[s]?[:\\s]*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);
            Matcher packingMatcher = packingPattern.matcher(descText);
            if (packingMatcher.find()) {
                product.setPackingList(packingMatcher.group(1).trim());
            }
        }
        
        // 8. 当前SKU规格
        Pattern skuIdPattern = Pattern.compile("_p_sku[=:](\\d+)");
        Matcher skuIdMatcher = skuIdPattern.matcher(html);
        String currentSkuId = skuIdMatcher.find() ? skuIdMatcher.group(1) : null;
        product.setSkuId(currentSkuId);
        
        // 从skuBase提取规格
        Map<String, String> specifications = extractSpecifications(html, currentSkuId);
        if (!specifications.isEmpty()) {
            try {
                product.setSpecifications(objectMapper.writeValueAsString(specifications));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // 9. URL
        Pattern urlPattern = Pattern.compile("\"url\"\\s*:\\s*\"(https://www\\.lazada[^\"]+)\"");
        Matcher urlMatcher = urlPattern.matcher(html);
        if (urlMatcher.find()) {
            product.setUrl(urlMatcher.group(1));
        }
        
        // 10. 分类名称
        Elements breadcrumbItems = doc.select(".breadcrumb_item_anchor span");
        if (!breadcrumbItems.isEmpty()) {
            product.setCategoryName(breadcrumbItems.last().text().trim());
        }
        
        return product;
    }
    
    /**
     * 从skuBase提取当前SKU的规格
     */
    private Map<String, String> extractSpecifications(String html, String currentSkuId) {
        Map<String, String> specs = new LinkedHashMap<>();
        if (currentSkuId == null) return specs;
        
        try {
            // 提取skuBase JSON
            String skuBaseJson = extractJsonObject(html, "\"skuBase\":");
            if (skuBaseJson == null) return specs;
            
            JsonNode skuBase = objectMapper.readTree(skuBaseJson);
            
            // 构建属性映射
            Map<String, Map<String, String>> propsMap = new HashMap<>();
            JsonNode properties = skuBase.get("properties");
            if (properties != null) {
                for (JsonNode prop : properties) {
                    String pid = prop.get("pid").asText();
                    String propName = prop.get("name").asText();
                    Map<String, String> values = new HashMap<>();
                    for (JsonNode val : prop.get("values")) {
                        values.put(val.get("vid").asText(), val.get("name").asText());
                    }
                    propsMap.put(pid, new HashMap<>() {{
                        put("name", propName);
                        putAll(values.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                e -> "v_" + e.getKey(), 
                                Map.Entry::getValue)));
                    }});
                }
            }
            
            // 找当前SKU的规格
            JsonNode skus = skuBase.get("skus");
            if (skus != null) {
                for (JsonNode sku : skus) {
                    if (currentSkuId.equals(sku.get("skuId").asText())) {
                        String propPath = sku.has("propPath") ? sku.get("propPath").asText() : "";
                        if (!propPath.isEmpty()) {
                            for (String pair : propPath.split(";")) {
                                String[] parts = pair.split(":");
                                if (parts.length == 2) {
                                    String pid = parts[0];
                                    String vid = parts[1];
                                    Map<String, String> propInfo = propsMap.get(pid);
                                    if (propInfo != null) {
                                        String propName = propInfo.get("name");
                                        String valName = propInfo.get("v_" + vid);
                                        if (propName != null && valName != null) {
                                            specs.put(propName, valName);
                                        }
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return specs;
    }
    
    /**
     * 从HTML中提取JSON对象
     */
    private String extractJsonObject(String html, String startMarker) {
        int startIdx = html.indexOf(startMarker);
        if (startIdx == -1) return null;
        
        int jsonStart = startIdx + startMarker.length();
        int braceCount = 0;
        int jsonEnd = jsonStart;
        boolean inString = false;
        boolean escapeNext = false;
        
        for (int i = jsonStart; i < html.length(); i++) {
            char c = html.charAt(i);
            
            if (escapeNext) {
                escapeNext = false;
                continue;
            }
            if (c == '\\') {
                escapeNext = true;
                continue;
            }
            if (c == '"' && !escapeNext) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            
            if (c == '{') {
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    jsonEnd = i + 1;
                    break;
                }
            }
        }
        
        return html.substring(jsonStart, jsonEnd);
    }
}
