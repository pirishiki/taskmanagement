package com.taskmanagement.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// PMD の UseUtilityClass（static しかないならインスタンスを作れなくせよ）は、ここでは当てはまらない
// Spring Boot が起動時にこのクラスのインスタンスを作って設定を読むので、作れなくするとアプリが起動しなくなる
@SuppressWarnings("PMD.UseUtilityClass")
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
