package com.taskmanagement.backend.concurrentedit;

import com.jayway.jsonpath.JsonPath;
import com.taskmanagement.backend.error.GlobalExceptionHandler;
import com.taskmanagement.backend.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節 CE-39：DB の安全網（JPA の @Version の例外）を 409 にするハンドラーのテスト
// 例外を投げるだけの仮の Controller に GlobalExceptionHandler をかけて、返事を確かめる（アプリも DB も起動しない）
class ConcurrentEditHandlerTest {

    // @Version の安全網が働いたときの例外を、そのまま投げる仮の入口
    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw new ObjectOptimisticLockingFailureException(Task.class, 1L);
        }
    }

    @Test
    @DisplayName("CE-39 ObjectOptimisticLockingFailureException は 500 ではなく 409（VERSION_CONFLICT・安全網の文・current なし）")
    void ce39OptimisticLockFailureIsConflict() {
        MockMvcTester tester = MockMvcTester.create(MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build());

        MvcTestResult result = tester.get().uri("/boom").exchange();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("VERSION_CONFLICT");
        assertThat((String) JsonPath.read(body, "$.detail"))
                .isEqualTo("ほかの画面で変更されています。最新の状態を読み込んでから、もう一度操作してください");
        Object parsed = JsonPath.read(body, "$");
        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) parsed).containsKey("current")).isFalse();
    }
}
