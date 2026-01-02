package com.lazada.service;

import com.lazada.entity.LazadaProduct;
import com.lazada.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {
    
    private final ProductRepository productRepository;
    
    public List<LazadaProduct> findAll(int limit) {
        return productRepository.findAll().stream().limit(limit).toList();
    }
    
    public boolean existsByUrl(String url) {
        return productRepository.existsByUrl(url);
    }
    
    public LazadaProduct save(LazadaProduct product) {
        return productRepository.save(product);
    }
    
    public void updateStatus(Long id, int status, String error) {
        productRepository.findById(id).ifPresent(p -> {
            p.setStatus(status);
            p.setErrorMessage(error);
            productRepository.save(p);
        });
    }
    
    public Map<String, Long> getStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("pending", productRepository.countByStatus(0));
        stats.put("processing", productRepository.countByStatus(1));
        stats.put("completed", productRepository.countByStatus(2));
        stats.put("failed", productRepository.countByStatus(-1));
        stats.put("total", productRepository.count());
        return stats;
    }
    
    public void deleteAll() {
        productRepository.deleteAll();
    }
}
