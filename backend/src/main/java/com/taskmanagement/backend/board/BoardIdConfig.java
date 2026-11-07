package com.taskmanagement.backend.board;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// ボードの番号の係（BoardIdInterceptor）を、/api/ のすべての入口に付ける
// ヘルスチェック（/api/health。Actuator）は外す：DB とつながっているかを確かめる入口なので、DB を読む係を通さない
@Configuration(proxyBeanMethods = false)
public class BoardIdConfig implements WebMvcConfigurer {

    private final BoardIds boardIds;

    public BoardIdConfig(BoardIds boardIds) {
        this.boardIds = boardIds;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BoardIdInterceptor(boardIds))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health", "/api/health/**");
    }
}
