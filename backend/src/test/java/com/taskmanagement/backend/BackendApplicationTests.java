package com.taskmanagement.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// アプリが起動できるかの確認。DB は Testcontainers の使い捨ての PostgreSQL を使う
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Test
    void contextLoads() {
        // 中身は空でよい。@SpringBootTest がアプリを起動し、起動に失敗すればこのテストが失敗する
    }

}
