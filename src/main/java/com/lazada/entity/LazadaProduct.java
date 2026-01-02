package com.lazada.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Map;

/**
 * Lazada商品实体
 */
@Data
@Entity
@Table(name = "products")
public class LazadaProduct {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 500)
    private String title;
    
    @Column(length = 50)
    private String price;
    
    @Column(length = 50)
    private String originalPrice;
    
    @Column(length = 50)
    private String discount;
    
    @Column(length = 200)
    private String dimensions;
    
    @Column(length = 100)
    private String weight;
    
    @Column(columnDefinition = "TEXT")
    private String images;
    
    @Column(columnDefinition = "TEXT")
    private String packingList;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(columnDefinition = "TEXT")
    private String specifications;
    
    @Column(length = 500, unique = true)
    private String url;
    
    @Column(length = 200)
    private String categoryName;
    
    @Column(length = 100)
    private String skuId;
    
    @Transient
    private Map<String, String> skuAttrs;
    
    @Column
    private Integer status = 0;
    
    @Column(length = 500)
    private String errorMessage;
}
