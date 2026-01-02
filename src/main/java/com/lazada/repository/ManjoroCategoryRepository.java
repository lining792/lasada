package com.lazada.repository;

import com.lazada.entity.ManjaroCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ManjoroCategoryRepository extends JpaRepository<ManjaroCategory, String> {
    List<ManjaroCategory> findAllByOrderByFullPath();
}
