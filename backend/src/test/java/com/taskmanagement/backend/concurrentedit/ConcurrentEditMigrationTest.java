package com.taskmanagement.backend.concurrentedit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節 CE-13：V5 まで当てた DB に、V6 を当てたときの移行のテスト
// アプリは起動せず、専用の使い捨ての PostgreSQL に Flyway を直接かける（V5 までと V6 を別々に当てるため）
class ConcurrentEditMigrationTest {

    @Test
    @DisplayName("CE-13 V5 までの DB にある行は、V6 を当てると version 0 になり、board_meta に 1 行（board_id つき）ができる")
    void ce13MigrationFromV5() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            // 準備：V5 まで当てて、列とタスクを SQL で直接入れる
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("5")
                    .load().migrate();
            jdbc.update("INSERT INTO board_columns (name, sort_order, is_done, is_fixed) "
                    + "VALUES ('確認待ち', 3, FALSE, FALSE)");
            jdbc.update("INSERT INTO tasks (text, priority, due_date, sort_order, column_id) "
                    + "SELECT 'もとからあるタスク', 'high', NULL, 0, min(id) FROM board_columns");

            // 実行：V6 を当てる（最新まで）
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

            // 確かめる：入れておいた行（基本の 3 列も）の version が 0。board_meta はちょうど 1 行で、board_id が入っている
            List<Long> columnVersions = jdbc.queryForList("SELECT version FROM board_columns", Long.class);
            assertThat(columnVersions).hasSize(4);
            assertThat(columnVersions).containsOnly(0L);
            List<Long> taskVersions = jdbc.queryForList("SELECT version FROM tasks", Long.class);
            assertThat(taskVersions).hasSize(1);
            assertThat(taskVersions).containsOnly(0L);
            Integer metaRows = jdbc.queryForObject("SELECT count(*) FROM board_meta", Integer.class);
            assertThat(metaRows).isEqualTo(1);
            String boardId = jdbc.queryForObject("SELECT board_id::text FROM board_meta", String.class);
            assertThat(boardId).isNotBlank();
        }
    }
}
