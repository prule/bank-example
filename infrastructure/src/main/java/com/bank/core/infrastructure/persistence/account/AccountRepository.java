package com.bank.core.infrastructure.persistence.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByUuid(@Param("id") String id);

    Optional<AccountEntity> findByAccountNumber(String accountNumber);
}
