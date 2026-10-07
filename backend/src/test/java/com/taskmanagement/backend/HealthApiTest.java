package com.taskmanagement.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

// ヘルスチェック（/api/health）のテスト
// アプリを丸ごと起動し、「生きてる？」と聞いて、返事を確かめる。DB は Testcontainers の使い捨ての PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthApiTest {

    // API を呼んで、返事を確かめる道具（ブラウザや curl の代わり）
    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("DB とつながっているとき、/api/health は 200 と UP を返す")
    void healthIsUp() {
        assertThat(mvc.get().uri("/api/health"))
                .hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("health 以外の Actuator の入口は外に見せない")
    void otherEndpointsAreHidden() {
        assertThat(mvc.get().uri("/api/env")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
