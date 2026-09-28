package com.example.kollus.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Kollus Upload API 호출용 {@link RestClient} 및 관련 빈을 정의합니다.
 */
@Configuration
@EnableConfigurationProperties(KollusProperties.class)
public class KollusApiConfig {

    /** Kollus Upload API base URL — 국내(kr) 서비스 고정값 */
    private static final String UPLOAD_API_BASE_URL = "https://c-api-kr.kollus.com/api";
    /** Kollus 패스스루 업로드 API base URL — 인코딩 없이 원본을 그대로 저장하는 전용 엔드포인트 */
    private static final String UPLOAD_API_PASSTHROUGH_BASE_URL = "https://upload.kr.kollus.com/api/v1";

    /**
     * Kollus Upload API 전용 {@link RestClient} 를 생성합니다.
     *
     * <p>인증 토큰은 요청마다 쿼리 파라미터({@code access_token})로 전달되므로
     * 여기서는 base URL 만 설정합니다.
     *
     * @return base URL 이 설정된 {@link RestClient}
     */
    @Bean
    public RestClient kollusUploadRestClient() {
        return RestClient.builder()
                .baseUrl(UPLOAD_API_BASE_URL)
                .build();
    }

    /**
     * Kollus 패스스루 업로드 API 전용 {@link RestClient} 를 생성합니다.
     *
     * @return base URL 이 설정된 {@link RestClient}
     */
    @Bean
    public RestClient kollusUploadPassthroughRestClient() {
        return RestClient.builder()
                .baseUrl(UPLOAD_API_PASSTHROUGH_BASE_URL)
                .build();
    }
}
