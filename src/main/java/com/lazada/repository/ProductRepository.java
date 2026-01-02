package com.lazada.repository;

import com.lazada.entity.LazadaProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<LazadaProduct, Long> {
    boolean existsByUrl(String url);
    long countByStatus(int status);
}
