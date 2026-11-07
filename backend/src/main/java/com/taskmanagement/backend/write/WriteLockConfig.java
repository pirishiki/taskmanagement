package com.taskmanagement.backend.write;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

// アプリの @Transactional が使うトランザクションの係を、鍵をとる WriteLockTransactionManager にする
// Spring Boot は、トランザクションの係がまだなければ標準の JpaTransactionManager を作る。ここで先に作るので、そちらは作られない
// 作り方は Spring Boot の標準と同じ（spring.transaction.* の設定も、customizers で同じように当てる）
@Configuration(proxyBeanMethods = false)
public class WriteLockConfig {

    // lockTimeout：書き込みの鍵を待つ時間の上限（application.properties の app.write-lock.timeout。例：5s）
    // afterLockCheck：鍵をとった直後の確かめ（ボードの番号の 2 回目の確かめ BoardIdGuard。イシュー #71）。なければ何もしない
    @Bean
    public PlatformTransactionManager transactionManager(ObjectProvider<TransactionManagerCustomizers> customizers,
            @Value("${app.write-lock.timeout}") Duration lockTimeout, ObjectProvider<AfterLockCheck> afterLockCheck) {
        WriteLockTransactionManager transactionManager = new WriteLockTransactionManager(lockTimeout,
                afterLockCheck.getIfAvailable(() -> AfterLockCheck.NONE));
        customizers.ifAvailable(each -> each.customize(transactionManager));
        return transactionManager;
    }
}
