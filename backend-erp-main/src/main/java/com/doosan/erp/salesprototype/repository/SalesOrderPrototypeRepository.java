package com.doosan.erp.salesprototype.repository;

import com.doosan.erp.salesprototype.entity.SalesOrderPrototype;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SalesOrderPrototypeRepository extends JpaRepository<SalesOrderPrototype, Long> {
}
