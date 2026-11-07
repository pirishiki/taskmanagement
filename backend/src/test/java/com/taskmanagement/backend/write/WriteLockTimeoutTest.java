package com.taskmanagement.backend.write;

import com.taskmanagement.backend.TestcontainersConfiguration;
import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.column.BoardColumnRepository;
import com.taskmanagement.backend.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

// 書き込みの鍵の待ちの上限のテスト（docs/二重送信の設計書.md の 4 節）
// ほかのトランザクションが鍵を握ったままだと、API の書き込みは上限まで待ってから 503 になる。握りを離せば、次の書き込みは通る
// 上限は application.properties の app.write-lock.timeout（ふだんは 5 秒）。このテストだけ 1 秒にして、速く終わるようにする
@SpringBootTest(properties = "app.write-lock.timeout=1s")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WriteLockTimeoutTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private BoardColumnRepository columnRepository;

    // 鍵を握るための、アプリとは別の DB の接続を借りる
    @Autowired
    private DataSource dataSource;

    // テストごとに、3列だけ・タスクが 1 件もない状態にする
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false));
        columnRepository.save(new BoardColumn("進行中", 1.0, true, false));
        columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true));
    }

    private MvcTestResult postTask() {
        return mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"牛乳を買う\"}").exchange();
    }

    @Test
    @DisplayName("DS-34 ほかのトランザクションが鍵を握ったままだと、書き込みは 503 になる。握りを離せば、次の書き込みは 201")
    void writeIsServiceUnavailableWhileLockIsHeld() throws SQLException {
        try (Connection holder = dataSource.getConnection()) {
            // 準備：別の接続でトランザクションを始め、アプリと同じ鍵を握ったままにする
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, WriteLockTransactionManager.LOCK_KEY);
                statement.execute();
            }

            // 実行と確かめる：鍵が取れないので、上限（1 秒）で待つのをやめて 503。タスクは作られない
            MvcTestResult blocked = postTask();
            assertThat(blocked).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(blocked).bodyJson().extractingPath("$.detail").asString().contains("別の書き込み");
            assertThat(taskRepository.count()).isZero();

            // 握りを離す（トランザクションを終えると、鍵は DB が自動で外す）
            holder.rollback();
        }

        // 次の書き込みは、待たずに通る
        assertThat(postTask()).hasStatus(HttpStatus.CREATED);
        assertThat(taskRepository.count()).isEqualTo(1);
    }
}
