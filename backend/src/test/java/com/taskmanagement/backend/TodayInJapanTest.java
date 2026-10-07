package com.taskmanagement.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// 「今日」を日本時間で決めているかのテスト（イシュー #55、品質チェック #30）
// 壁の時計を「世界標準時 2026-10-06 16:00」で止める。日本時間では 2026-10-07 の朝 1 時なので、「今日」は 10-07 になるはず
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TodayInJapanTest.StoppedClock.class})
class TodayInJapanTest {

    // 世界標準時ではまだ 10-06、日本時間ではもう 10-07 になっている瞬間
    private static final Instant JUST_AFTER_MIDNIGHT_IN_JAPAN = Instant.parse("2026-10-06T16:00:00Z");

    // テストのときだけ、壁の時計を止めた時計に取り替える（@Primary：同じ種類の部品が 2 つあるときは、こちらを使う）
    @TestConfiguration
    static class StoppedClock {
        @Bean
        @Primary
        Clock stoppedClock() {
            return Clock.fixed(JUST_AFTER_MIDNIGHT_IN_JAPAN, ClockConfig.ZONE);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("壁の時計は日本時間（Asia/Tokyo）")
    void clockIsJapanTime() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(ClockConfig.ZONE);
    }

    @Test
    @DisplayName("日本の夜中 1 時に書き出すと、ファイル名は日本の日付（10-07）になる")
    void exportFileNameUsesJapaneseDate() {
        assertThat(mvc.get().uri("/api/tasks/export"))
                .headers().hasValue(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tasks-2026-10-07.json\"");
    }
}
