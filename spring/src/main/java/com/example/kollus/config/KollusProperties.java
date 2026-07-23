package com.example.kollus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Kollus 연동에 필요한 커스텀 프로퍼티.
 *
 * <p>{@code application.yaml} / {@code application-local.yaml} 의 {@code kollus.*} 값에 바인딩됩니다.
 * 민감 정보({@code secretKey}, {@code customKey}, {@code apiToken}, {@code categoryKey})는
 * 커밋 대상이 아닌 {@code application-local.yaml} 에서 주입합니다.
 *
 * @param secretKey       JWT 서명 키 (서비스 계정 &gt; 보안키)
 * @param customKey       플레이어 URL 커스텀 키 (서비스 계정 &gt; 커스텀키)
 * @param apiToken        Upload API 액세스 토큰 (서비스 계정 &gt; API 토큰)
 * @param categoryKey     업로드 카테고리 키 (콘텐츠 &gt; 카테고리)
 * @param sampleMck       샘플 플레이어 페이지 기본 노출 MCK
 * @param nextEpisodeMcks 다음 회차 기능 샘플 MCK 목록
 * @param multidrm        멀티 DRM 전용 Kollus 계정 키 (일반 재생과 별도 계정)
 * @param inka            멀티 DRM(inkaDRM/Pallycon) 계정 및 서버 설정
 */
@ConfigurationProperties(prefix = "kollus")
public record KollusProperties(
        String secretKey,
        String customKey,
        String apiToken,
        String categoryKey,
        String sampleMck,
        List<String> nextEpisodeMcks,
        MultiDrm multidrm,
        Inka inka
) {
    public KollusProperties {
        // 미설정 시 NPE 방지를 위해 빈 목록/빈 객체로 정규화
        if (nextEpisodeMcks == null) {
            nextEpisodeMcks = List.of();
        }
        if (multidrm == null) {
            multidrm = new MultiDrm(null, null);
        }
        if (inka == null) {
            inka = new Inka(null, null, null, null, null, null);
        }
    }

    /**
     * 멀티 DRM 전용 Kollus 계정 키.
     * 멀티 DRM 재생은 일반 재생과 다른 Kollus 계정을 사용하므로 별도 키가 필요합니다.
     *
     * @param securityKey 멀티 DRM 전용 JWT 서명 키
     * @param customKey   멀티 DRM 전용 플레이어 URL 커스텀 키
     */
    public record MultiDrm(
            String securityKey,
            String customKey
    ) {}

    /**
     * inkaDRM(Pallycon) 계정 및 라이선스 서버 설정.
     *
     * @param accessKey      inkaDRM Access Key (해시 계산용)
     * @param siteKey        inkaDRM Site Key (라이선스 룰 AES-256-CBC 암호화 키, 32바이트)
     * @param siteId         inkaDRM Site ID
     * @param iv             라이선스 룰 암호화 IV (16바이트)
     * @param licenseUrl     DoveRunner 라이선스 서버 URL
     * @param certificateUrl DoveRunner FairPlay 인증서 서버 URL
     */
    public record Inka(
            String accessKey,
            String siteKey,
            String siteId,
            String iv,
            String licenseUrl,
            String certificateUrl
    ) {}
}
