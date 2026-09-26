package com.taskmanagement.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// テスト用の設定：テストのたびに、Docker で使い捨ての PostgreSQL（開発用と同じ 17）を起動する
// テストのクラスに @Import(TestcontainersConfiguration.class) を付けると使える
// 開発用の DB（docker compose の localhost:5432）は使わないので、起動していなくてもテストが動き、開発用のデータも汚さない
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // @ServiceConnection：起動したコンテナの接続先（ポート番号・ユーザー名など）を、Spring Boot に自動で教える
    // （application.properties の localhost:5432 の代わりに、このコンテナにつながる）
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }
}
