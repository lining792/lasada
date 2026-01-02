package com.lazada.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Manjaro Supply 分类实体
 */
@Data
@Entity
@Table(name = "manjaro_categories")
public class ManjaroCategory {
    
    @Id
    @Column(length = 50)
    private String categoryId;
    
    @Column(length = 255)
    private String name;
    
    @Column(length = 1000)
    private String fullPath;
    
    @Column(length = 50)
    private String parentId;
    
    @Column
    private Integer level;
    
    public ManjaroCategory() {}
    
    public ManjaroCategory(String categoryId, String name, String fullPath, String parentId, Integer level) {
        this.categoryId = categoryId;
        this.name = name;
        this.fullPath = fullPath;
        this.parentId = parentId;
        this.level = level;
    }
}
