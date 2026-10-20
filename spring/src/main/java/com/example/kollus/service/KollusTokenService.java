package com.example.kollus.service;

import com.example.kollus.dto.TokenResponse;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kollus 플레이어 JWT 토큰 생성/서명 유틸리티.
 *
 * <p>외부 JWT 라이브러리 없이 HS256(HMAC-SHA256) 방식으로 Kollus 전용 페이로드를 직접 인코딩합니다.
 */
@Service
public class KollusTokenService {

    /** Kollus 플레이어 base URL — 국내(kr) 서비스 고정값 */
    private static final String DEFAULT_PLAYER_BASE_URL = "https://v.kr.kollus.com/";
    /** HMAC 서명 알고리즘 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** JWT 기본 만료 시간 (분) */
    private static final int DEFAULT_EXPIRY_MINUTES = 3;
    /** 1분 = 60초 */
    private static final int SECONDS_PER_MINUTE = 60;
    /** JWT 헤더 (typ, alg 고정) */
    private static final Map<String, Object> JWT_HEADER = Map.of("typ", "JWT", "alg", "HS256");

    private final ObjectMapper objectMapper;
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();

    public KollusTokenService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Kollus 플레이어 JWT 토큰과 재생 URL을 생성합니다.
     *
     * @param params 토큰 생성 파라미터 (securityKey, customKey, mck 필수 / 그 외 선택)
     * @return JWT 토큰, 재생 URL, 페이로드
     */
    public TokenResponse generatePlayerToken(Map<String, Object> params) {
        String securityKey = asString(params.get("securityKey"));
        String customKey = asString(params.get("customKey"));
        String mck = asString(params.get("mck"));
        int expt = asInt(params.get("expt"), DEFAULT_EXPIRY_MINUTES);

        // 1. Payload 생성
        long exptInSeconds = Instant.now().getEpochSecond() + (long) SECONDS_PER_MINUTE * expt;

        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "cuid", params.get("cuid"));
        payload.put("expt", exptInSeconds);

        // 전역 옵션 (next_episode 는 미지정 시 true 기본값)
        Object nextEpisode = params.containsKey("next_episode") ? params.get("next_episode") : Boolean.TRUE;
        payload.put("next_episode", nextEpisode);
        putIfTruthy(payload, "playback_rates", params.get("playback_rates"));
        putIfPresent(payload, "playcallback_ignore", params.get("playcallback_ignore"));
        putIfTruthy(payload, "video_watermarking_code_policy", params.get("video_watermarking_code_policy"));

        // mc 항목 구성: [intro?] + main + [outro?]
        List<Map<String, Object>> mc = new ArrayList<>();

        String intro = asString(params.get("intro"));
        if (intro != null) {
            mc.add(Map.of("mckey", intro, "intr", true));
        }

        Map<String, Object> main = new LinkedHashMap<>();
        main.put("mckey", mck);
        putIfTruthy(main, "mcpf", params.get("mcpf"));
        putIfTruthy(main, "title", params.get("title"));
        putIfPresent(main, "seek", params.get("seek"));
        putIfPresent(main, "seekable_end", params.get("seekable_end"));
        putIfTruthy(main, "play_section", params.get("play_section"));
        // mc_subtitle_policy 가 있으면 항목별 우선 적용, 없으면 전역 subtitle_policy 사용
        Object mcSubtitlePolicy = params.get("mc_subtitle_policy");
        Object subtitlePolicy = params.get("subtitle_policy");
        if (isTruthy(mcSubtitlePolicy)) {
            main.put("subtitle_policy", mcSubtitlePolicy);
        } else if (isTruthy(subtitlePolicy)) {
            main.put("subtitle_policy", subtitlePolicy);
        }
        mc.add(main);

        String outro = asString(params.get("outro"));
        if (outro != null) {
            mc.add(Map.of("mckey", outro, "intr", true));
        }

        payload.put("mc", mc);

        // 2. JWT 인코딩 (HS256)
        String token = encodeJwt(payload, securityKey);

        // 3. 재생 URL 생성
        String link = DEFAULT_PLAYER_BASE_URL + "s?jwt=" + token + "&custom_key=" + customKey;

        return new TokenResponse(token, link, payload);
    }

    /**
     * 멀티 DRM(Widevine/PlayReady/FairPlay) 재생용 플레이어 URL을 생성합니다.
     *
     * <p>mc 항목별 {@code drm_policy} 는 호출자({@link KollusMultiDrmService})가 조립해 전달하며,
     * 일반 재생과 다른 <b>멀티 DRM 전용 계정 키</b>로 서명/URL 을 구성합니다.
     *
     * @param clientUserId        서비스 측 사용자 식별자 (cuid)
     * @param mediaContentEntries drm_policy 를 포함한 mc 항목 배열
     * @param securityKey         멀티 DRM 전용 JWT 서명 키
     * @param customKey           멀티 DRM 전용 플레이어 URL 커스텀 키
     * @return iframe src 에 사용할 플레이어 URL
     */
    public String buildMultiDrmPlayerUrl(String clientUserId,
                                         List<Map<String, Object>> mediaContentEntries,
                                         String securityKey,
                                         String customKey) {
        long exptInSeconds = Instant.now().getEpochSecond()
                + (long) SECONDS_PER_MINUTE * DEFAULT_EXPIRY_MINUTES;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cuid", clientUserId);
        payload.put("mc", mediaContentEntries);
        payload.put("expt", exptInSeconds);

        String token = encodeJwt(payload, securityKey);
        return DEFAULT_PLAYER_BASE_URL + "s?jwt=" + token + "&custom_key=" + customKey;
    }

    /**
     * Kollus 콜백 응답용 JWT를 HS256으로 서명합니다.
     *
     * @param payload   JWT 페이로드
     * @param securityKey HMAC 서명 키
     * @return 서명된 JWT 문자열
     */
    public String signCallbackJwt(Object payload, String securityKey) {
        return encodeJwt(payload, securityKey);
    }

    /**
     * {@code header.payload.signature} 형태의 JWT를 생성합니다.
     */
    private String encodeJwt(Object payload, String securityKey) {
        String header = base64UrlJson(JWT_HEADER);
        String body = base64UrlJson(payload);
        String content = header + "." + body;
        String signature = sign(content, securityKey);
        return content + "." + signature;
    }

    /**
     * 객체를 JSON 직렬화 후 base64url 인코딩합니다.
     */
    private String base64UrlJson(Object value) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            return base64Url.encodeToString(json);
        } catch (JacksonException e) {
            // 직렬화 실패는 프로그래밍 오류에 가까우므로 언체크 예외로 전환
            throw new IllegalStateException("JWT payload 직렬화 실패", e);
        }
    }

    /**
     * HMAC-SHA256 서명을 base64url 로 반환합니다.
     */
    private String sign(String content, String securityKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(securityKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return base64Url.encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 은 표준 JVM 제공 알고리즘이므로 정상 환경에서는 발생하지 않음
            throw new IllegalStateException("JWT 서명 실패", e);
        }
    }

    // ── 파라미터 처리 헬퍼 ──────────────────────────────────────

    /** 값이 존재(null 아님)하면 그대로 담습니다. */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** 값이 존재하고 비어있지 않으면 담습니다. */
    private void putIfTruthy(Map<String, Object> target, String key, Object value) {
        if (isTruthy(value)) {
            target.put(key, value);
        }
    }

    /** null, 빈 문자열, 0, false 는 값이 없는 것으로 취급합니다. */
    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    /** 정수로 파싱하되 실패/미지정 또는 0 이면 기본값을 반환합니다. */
    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            int i = n.intValue();
            return i == 0 ? defaultValue : i;
        }
        if (value != null) {
            try {
                int i = Integer.parseInt(value.toString().trim());
                return i == 0 ? defaultValue : i;
            } catch (NumberFormatException e) {
                // 숫자로 파싱할 수 없으면 기본값 사용
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
