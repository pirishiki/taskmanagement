package com.taskmanagement.backend.task;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// URL の「?priority=high」「?due=overdue」の小文字を、enum（TaskPriority・DueFilter）に変える方法を Spring に教える
// これがないと、Spring は enum の名前そのもの（HIGH・OVERDUE のような大文字）しか受け付けない
// @Configuration：Spring が起動するときに読み込む「設定」のクラスであることを示す
@Configuration
public class QueryParamConverters implements WebMvcConfigurer {

    // 「文字 → enum」の変え方を登録する。中身は、それぞれの enum にある fromValue を呼ぶだけ
    // 決まった値以外なら fromValue が例外を投げ、Spring が 400（ProblemDetail）にして返す
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, TaskPriority.class, TaskPriority::fromValue);
        registry.addConverter(String.class, DueFilter.class, DueFilter::fromValue);
    }
}
