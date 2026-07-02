package com.gying.movie;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.gying.movie.mapper")
@EnableScheduling
public class GyingMovieApplication {

    public static void main(String[] args) {
        SpringApplication.run(GyingMovieApplication.class, args);
    }

}
