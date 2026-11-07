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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

// 書き込みの鍵の待ちの上限のテスト（docs/二重送信の設計書.md の 4 節）
// ほかのトランザクションが鍵を握ったままだと、API の書き込みは上限まで待ってから 503 になる。握りを離せば、次の書き込みは通る
// 上限は application.properties の app.write-lock.timeout（ふだんは 5 秒）。このテストだけ 1 秒にして、速く終わるようにする
@SpringBootTest(properties = "app.write-lock.timeout=1s")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WriteLockTimeoutTest {

    // 待ちの上限が残っていないかを確かめるために、接続プールから同時に借りる接続の数
    // （プールの上限 10 本のうち、鍵を握っている 1 本を除いても足りる数）
    private static final int BORROWED_CONNECTIONS = 5;

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
    @DisplayName("DS-34 ほかのトランザクションが鍵を握ったままだと、書き込みは 503 になる。待ちの上限は接続に残らず、"
            + "握りを離せば、次の書き込みは 201")
    void ds34WriteIsServiceUnavailableWhileLockIsHeld() throws SQLException {
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

            // 待ちの上限（lock_timeout）は、そのトランザクションの中だけの設定。接続プールに返った接続には残らない
            assertPooledConnectionsHaveDefaultLockTimeout();

            // 握りを離す（トランザクションを終えると、鍵は DB が自動で外す）
            holder.rollback();
        }

        // 次の書き込みは、待たずに通る
        assertThat(postTask()).hasStatus(HttpStatus.CREATED);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    // 接続プールから、接続を何本か同時に借りて、どれも lock_timeout が既定（0）かを確かめる
    // 同時に借りるので、別々の接続になる。プール（HikariCP）は、同じスレッドが最後に返した接続を最初に貸すので、
    // 1 本目は、さっき 503 になった書き込みが使った接続になる
    private void assertPooledConnectionsHaveDefaultLockTimeout() throws SQLException {
        assertBorrowedLockTimeouts(BORROWED_CONNECTIONS);
    }

    // 1 本借りて確かめ、その接続を開いたまま、残りの本数を借りる（返す前に次を借りるので、同じ接続が二度来ない）
    // try-with-resources で、確かめ終わった接続は必ずプールへ返す
    private void assertBorrowedLockTimeouts(int remaining) throws SQLException {
        if (remaining == 0) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW lock_timeout")) {
            String lockTimeout = null;
            if (result.next()) {
                lockTimeout = result.getString(1);
            }
            assertThat(lockTimeout).isEqualTo("0");
            assertBorrowedLockTimeouts(remaining - 1);
        }
    }
}
