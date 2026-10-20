package com.example.kollus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * Kollus 연동에 필요한 커스텀 프로퍼티.
 *
 * <p>{@code application.yaml} / {@code application-local.yaml} 의 {@code kollus.*} 값에 바인딩됩니다.
 * 민감 정보({@code securityKey}, {@code customKey}, {@code apiToken}, {@code categoryKey})는
 * 커밋 대상이 아닌 {@code application-local.yaml} 에서 주입합니다.
 *
 * @param securityKey     JWT 서명 키 (서비스 계정 &gt; 보안키)
 * @param customKey       플레이어 URL 커스텀 키 (서비스 계정 &gt; 커스텀키)
 * @param apiToken        Upload API 액세스 토큰 (서비스 계정 &gt; API 토큰)
 * @param categoryKey     업로드 카테고리 키 (콘텐츠 &gt; 카테고리)
 * @param sampleMck       샘플 플레이어 페이지 기본 노출 MCK
 * @param nextEpisodeMcks 다음 회차 기능 샘플 MCK 목록 (콤마 구분 문자열, 예: "mckA,mckB")
 * @param multidrm        멀티 DRM 전용 Kollus 계정 키 (일반 재생과 별도 계정)
 * @param doverunner      멀티 DRM(DoveRunner) 계정 및 서버 설정
 */
@ConfigurationProperties(prefix = "kollus")
public record KollusProperties(
        String securityKey,
        String customKey,
        String apiToken,
        String categoryKey,
        String sampleMck,
        String nextEpisodeMcks,
        MultiDrm multidrm,
        Doverunner doverunner
) {
    public KollusProperties {
        // 미설정 시 NPE 방지를 위해 빈 문자열/빈 객체로 정규화
        if (nextEpisodeMcks == null) {
            nextEpisodeMcks = "";
        }
        if (multidrm == null) {
            multidrm = new MultiDrm(null, null);
        }
        if (doverunner == null) {
            doverunner = new Doverunner(null, null, null, null, null, null);
        }
    }

    /**
     * 콤마 구분 문자열인 {@code nextEpisodeMcks} 를 목록으로 변환합니다.
     * 각 항목의 앞뒤 공백을 제거하고 빈 항목은 제외합니다.
     *
     * @return 다음 회차 샘플 MCK 목록 (미설정 시 빈 목록)
     */
    public List<String> nextEpisodeMckList() {
        return Arrays.stream(nextEpisodeMcks.split(","))
                .map(String::trim)
                .filter(mck -> !mck.isEmpty())
                .toList();
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
     * DoveRunner 멀티 DRM 계정 및 라이선스 서버 설정.
     *
     * @param accessKey      DoveRunner Access Key (해시 계산용)
     * @param siteKey        DoveRunner Site Key (라이선스 룰 AES-256-CBC 암호화 키, 32바이트)
     * @param siteId         DoveRunner Site ID
     * @param iv             라이선스 룰 암호화 IV (16바이트)
     * @param licenseUrl     DoveRunner 라이선스 서버 URL
     * @param certificateUrl DoveRunner FairPlay 인증서 서버 URL
     */
    public record Doverunner(
            String accessKey,
            String siteKey,
            String siteId,
            String iv,
            String licenseUrl,
            String certificateUrl
    ) {}
}
