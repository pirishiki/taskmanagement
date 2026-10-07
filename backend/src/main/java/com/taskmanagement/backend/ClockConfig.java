package com.taskmanagement.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

// アプリ全体で使う「壁の時計」（イシュー #55、品質チェック #30）
// 「今日」をサーバーの時計で決めると、AWS のサーバー（ふつう世界標準時）では、日本の朝 9 時まで「今日」が前の日になる
// そこで、日本時間に合わせた時計を 1 つ用意し、「今日」や「今」は必ずこの時計で決める（LocalDate.now(clock) のように使う）
// テストでは、好きな日時で止めた時計に取り替えられる
@Configuration
public class ClockConfig {

    // このアプリの「今日」を決める時間帯
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
