package com.lazada.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazada.entity.ManjaroCategory;
import com.lazada.repository.ManjoroCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {
    
    private final ManjoroCategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public long count() {
        return categoryRepository.count();
    }
    
    public List<ManjaroCategory> findAll() {
        return categoryRepository.findAll();
    }
    
    @Transactional
    public int importFromJson() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("manjaro_categories.json")) {
            if (is == null) {
                log.error("找不到 manjaro_categories.json");
                return 0;
            }
            
            JsonNode root = objectMapper.readTree(is);
            if (!root.isArray()) {
                log.error("JSON格式错误");
                return 0;
            }
            
            List<ManjaroCategory> categories = new ArrayList<>();
            for (JsonNode node : root) {
                ManjaroCategory cat = new ManjaroCategory();
                cat.setCategoryId(node.get("id").asText());
                cat.setName(node.get("name").asText());
                cat.setFullPath(node.get("full_path").asText());
                cat.setLevel(3);
                categories.add(cat);
            }
            
            categoryRepository.deleteAll();
            categoryRepository.saveAll(categories);
            
            log.info("导入了 {} 个分类", categories.size());
            return categories.size();
            
        } catch (Exception e) {
            log.error("导入分类失败: {}", e.getMessage());
            return 0;
        }
    }
    
    @Transactional
    public void deleteAll() {
        categoryRepository.deleteAll();
    }
}
