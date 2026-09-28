package com.example.kollus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Kollus 연동 샘플(Spring Boot) 애플리케이션 진입점.
 * 플레이어 JWT 발급, 콘텐츠 업로드 프록시, 콜백 수신, 멀티 DRM 재생 샘플을 하나의 서버로 제공합니다.
 */
@SpringBootApplication
public class KollusApplication {

    /**
     * Spring Boot 애플리케이션을 기동합니다.
     *
     * @param args 커맨드라인 인자 (Spring Boot 표준 옵션, 예: {@code --server.port=8081})
     */
    public static void main(String[] args) {
        SpringApplication.run(KollusApplication.class, args);
    }

}
