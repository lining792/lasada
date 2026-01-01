package com.lazada.entity;

import lombok.Data;
import java.util.Map;

/**
 * Lazada商品实体
 */
@Data
public class LazadaProduct {
    
    /** 商品标题 */
    private String title;
    
    /** 现价 */
    private String price;
    
    /** 原价 */
    private String originalPrice;
    
    /** 折扣 */
    private String discount;
    
    /** 尺寸 */
    private String dimensions;
    
    /** 重量 */
    private String weight;
    
    /** 图片URL，逗号分隔 */
    private String images;
    
    /** 包装清单 */
    private String packingList;
    
    /** 产品描述 */
    private String description;
    
    /** 规格，JSON格式 如 {"Color Family": "Pink", "Variation": "Basics"} */
    private String specifications;
    
    /** 商品URL */
    private String url;
    
    /** 分类名称 */
    private String categoryName;
    
    /** SKU ID */
    private String skuId;
    
    /** SKU属性，所有属性键值对 */
    private Map<String, String> skuAttrs;
}
