package com.example.kollus.service;

import com.example.kollus.config.KollusProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 멀티 DRM(Widevine/PlayReady/FairPlay) 재생 URL 생성 서비스.
 *
 * <p>요청 User-Agent 를 분석해 브라우저에 맞는 DRM 종류/스트리밍 방식을 자동 선택하고,
 * inkaDRM(Pallycon) 라이선스 룰을 AES-256-CBC 로 암호화 + SHA-256 해시를 계산해
 * {@code pallycon-customdata-v2} 커스텀 헤더 페이로드를 조립합니다. 실제 JWT 서명/URL 구성은
 * 멀티 DRM 전용 계정 키로 {@link KollusTokenService#buildMultiDrmPlayerUrl}에 위임합니다.
 */
@Service
public class KollusMultiDrmService {

    /** inkaDRM 라이선스 룰 (고정값) — AES-256-CBC 로 암호화되어 라이선스 토큰으로 사용됩니다. */
    private static final Map<String, Object> LICENSE_RULE = buildLicenseRule();

    /**
     * User-Agent 브라우저 식별자 → DRM 종류/스트리밍 방식 매핑 (검사 순서 중요).
     * CriOS(iOS Chrome)를 Chrome/Safari 보다 먼저 검사해야 하므로 {@link LinkedHashMap} 으로 순서를 보장합니다.
     */
    private static final Map<String, StreamingInfo> BROWSER_DRM_MAP = buildBrowserDrmMap();

    /** 매칭되는 브라우저가 없을 때의 기본 DRM/스트리밍 방식 (Widevine/dash). */
    private static final String DEFAULT_BROWSER = "Chrome";

    /** custom_header 키 이름 (Pallycon custom data v2). */
    private static final String CUSTOM_HEADER_KEY = "pallycon-customdata-v2";

    /** AES 암호화 방식 (PKCS7 패딩). */
    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /** UTC 타임스탬프 포맷 (예: 2026-07-10T05:30:00Z). */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final KollusTokenService tokenService;
    private final KollusProperties properties;
    private final ObjectMapper objectMapper;

    public KollusMultiDrmService(KollusTokenService tokenService,
                                 KollusProperties properties,
                                 ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 멀티 DRM 재생용 플레이어 URL을 생성합니다.
     * User-Agent 를 분석해 브라우저에 맞는 DRM 종류/스트리밍 방식을 자동 선택합니다.
     *
     * @param clientUserId    서비스 측 사용자 식별자 (cuid)
     * @param mediaContentKey Kollus 미디어 콘텐츠 키
     * @param contentId       멀티 DRM 콘텐츠 ID (Kollus 업로드 파일 키)
     * @param userAgent       요청 User-Agent (DRM/스트리밍 방식 판별용)
     * @return iframe src 에 사용할 플레이어 URL
     * @throws IllegalStateException 멀티 DRM/inkaDRM 설정 누락 또는 암호화 실패 시
     */
    public String buildPlayerUrl(String clientUserId, String mediaContentKey, String contentId, String userAgent) {
        validateConfig();

        StreamingInfo streaming = resolveStreamingInfo(userAgent);
        KollusProperties.Inka inka = properties.inka();

        Map<String, Object> customHeader = new LinkedHashMap<>();
        customHeader.put("key", CUSTOM_HEADER_KEY);
        customHeader.put("value", buildCustomHeaderPayload(clientUserId, contentId, streaming.drmType()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("license_url", inka.licenseUrl());
        data.put("certificate_url", inka.certificateUrl() + "?siteId=" + inka.siteId());
        data.put("custom_header", customHeader);

        Map<String, Object> drmPolicy = new LinkedHashMap<>();
        drmPolicy.put("kind", "inka");
        drmPolicy.put("streaming_type", streaming.streamingType());
        drmPolicy.put("data", data);

        Map<String, Object> mediaContentEntry = new LinkedHashMap<>();
        mediaContentEntry.put("mckey", mediaContentKey);
        mediaContentEntry.put("drm_policy", drmPolicy);

        return tokenService.buildMultiDrmPlayerUrl(
                clientUserId,
                List.of(mediaContentEntry),
                properties.multidrm().securityKey(),
                properties.multidrm().customKey());
    }

    /**
     * User-Agent 문자열로 브라우저별 DRM 종류와 스트리밍 방식을 판별합니다.
     * 매칭되는 브라우저가 없으면 Widevine/dash 를 기본값으로 사용합니다.
     */
    private StreamingInfo resolveStreamingInfo(String userAgent) {
        String ua = userAgent != null ? userAgent : "";

        StreamingInfo matched = BROWSER_DRM_MAP.get(DEFAULT_BROWSER);
        for (Map.Entry<String, StreamingInfo> entry : BROWSER_DRM_MAP.entrySet()) {
            if (ua.contains(entry.getKey())) {
                matched = entry.getValue();
                break;
            }
        }

        // Mac용 Edge는 Chromium 엔진을 사용하므로 PlayReady 가 아닌 Widevine 으로 재분류
        if (ua.contains("Macintosh") && ua.contains("Edg")) {
            matched = BROWSER_DRM_MAP.get(DEFAULT_BROWSER);
        }

        return matched;
    }

    /**
     * {@code pallycon-customdata-v2} 헤더에 담을 inkaDRM 커스텀 페이로드를 생성합니다.
     * (base64(JSON) 형태)
     */
    private String buildCustomHeaderPayload(String clientUserId, String contentId, String drmType) {
        KollusProperties.Inka inka = properties.inka();

        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String token = buildEncryptedLicenseToken();

        // 해시 입력 순서는 Pallycon 규격 고정: accessKey + drmType + siteId + userId + cid + token + timestamp
        String hashInput = inka.accessKey() + drmType + inka.siteId()
                + clientUserId + contentId + token + timestamp;
        String hash = Base64.getEncoder().encodeToString(sha256(hashInput));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("drm_type", drmType);
        payload.put("site_id", inka.siteId());
        payload.put("user_id", clientUserId);
        payload.put("cid", contentId);
        payload.put("token", token);
        payload.put("timestamp", timestamp);
        payload.put("hash", hash);

        byte[] json = objectMapper.writeValueAsBytes(payload);
        return Base64.getEncoder().encodeToString(json);
    }

    /**
     * 라이선스 룰을 AES-256-CBC 로 암호화해 inkaDRM 토큰(base64)을 생성합니다.
     */
    private String buildEncryptedLicenseToken() {
        KollusProperties.Inka inka = properties.inka();
        try {
            byte[] rule = objectMapper.writeValueAsBytes(LICENSE_RULE);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(inka.siteKey().getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(inka.iv().getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            byte[] encrypted = cipher.doFinal(rule);
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            // Site Key(32바이트)/IV(16바이트) 길이 불일치 등 설정 오류 또는 JVM 알고리즘 미지원
            throw new IllegalStateException(
                    "inkaDRM 라이선스 룰 암호화에 실패했습니다 (Site Key 32바이트/IV 16바이트 확인): " + e.getMessage(), e);
        }
    }

    /** UTF-8 문자열의 SHA-256 원시 해시(32바이트)를 반환합니다. */
    private byte[] sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 표준 JVM 제공 알고리즘이므로 정상 환경에서는 발생하지 않음
            throw new IllegalStateException("SHA-256 해시 계산에 실패했습니다.", e);
        }
    }

    /**
     * 멀티 DRM 재생에 필요한 설정이 모두 존재하는지 검증합니다.
     * 누락 시 명확한 안내 메시지와 함께 예외를 던집니다.
     */
    private void validateConfig() {
        KollusProperties.MultiDrm multidrm = properties.multidrm();
        if (isBlank(multidrm.securityKey()) || isBlank(multidrm.customKey())) {
            throw new IllegalStateException(
                    "멀티 DRM 전용 키가 설정되지 않았습니다. application-local.yaml 의 "
                            + "kollus.multidrm.security-key / custom-key 를 입력하세요.");
        }

        KollusProperties.Inka inka = properties.inka();
        if (isBlank(inka.accessKey()) || isBlank(inka.siteKey()) || isBlank(inka.siteId())) {
            throw new IllegalStateException(
                    "inkaDRM(Pallycon) 키가 설정되지 않았습니다. application-local.yaml 의 "
                            + "kollus.inka.access-key / site-key / site-id 를 입력하세요.");
        }
        if (isBlank(inka.iv()) || isBlank(inka.licenseUrl()) || isBlank(inka.certificateUrl())) {
            throw new IllegalStateException(
                    "inkaDRM iv / license-url / certificate-url 설정이 누락되었습니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    // ── 상수 빌더 ──────────────────────────────────────────────

    /** inkaDRM 라이선스 룰 고정값을 조립합니다 (순서 보존을 위해 LinkedHashMap 사용). */
    private static Map<String, Object> buildLicenseRule() {
        Map<String, Object> widevine = new LinkedHashMap<>();
        widevine.put("security_level", 1);
        widevine.put("disable_analog_output", false);
        widevine.put("hdcp_srm_rule", "HDCP_SRM_RULE_NONE");
        widevine.put("override_device_revocation", true);

        Map<String, Object> playready = new LinkedHashMap<>();
        playready.put("security_level", 150);
        playready.put("digital_video_protection_level", 100);
        playready.put("analog_video_protection_level", 100);
        playready.put("digital_audio_protection_level", 100);
        playready.put("require_hdcp_type_1", false);

        Map<String, Object> fairplay = new LinkedHashMap<>();
        fairplay.put("hdcp_enforcement", -1);
        fairplay.put("allow_airplay", true);
        fairplay.put("allow_av_adapter", true);

        Map<String, Object> securityEntry = new LinkedHashMap<>();
        securityEntry.put("widevine", widevine);
        securityEntry.put("playready", playready);
        securityEntry.put("fairplay", fairplay);

        Map<String, Object> playbackPolicy = new LinkedHashMap<>();
        playbackPolicy.put("persistent", false);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("policy_version", 2);
        rule.put("playback_policy", playbackPolicy);
        rule.put("security_policy", List.of(securityEntry));
        return rule;
    }

    /** User-Agent → DRM/스트리밍 매핑을 검사 순서대로 조립합니다. */
    private static Map<String, StreamingInfo> buildBrowserDrmMap() {
        Map<String, StreamingInfo> map = new LinkedHashMap<>();
        map.put("CriOS", new StreamingInfo("FairPlay", "hls"));
        map.put("Edge", new StreamingInfo("PlayReady", "dash"));
        map.put("Edg", new StreamingInfo("PlayReady", "dash"));
        map.put("Firefox", new StreamingInfo("Widevine", "dash"));
        map.put("Chrome", new StreamingInfo("Widevine", "dash"));
        map.put("Safari", new StreamingInfo("FairPlay", "hls"));
        map.put("Opera", new StreamingInfo("PlayReady", "dash"));
        map.put("MSIE", new StreamingInfo("PlayReady", "dash"));
        map.put("Trident", new StreamingInfo("PlayReady", "dash"));
        return map;
    }

    /** 브라우저에 매핑되는 DRM 종류와 스트리밍 방식. */
    private record StreamingInfo(String drmType, String streamingType) {}
}
