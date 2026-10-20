package com.example.kollus.controller;

import com.example.kollus.config.KollusProperties;
import com.example.kollus.dto.TokenResponse;
import com.example.kollus.service.KollusTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Kollus 콜백 처리 라우터.
 * 재생(Play), DRM, 다음 회차(Next Episode), LMS 콜백을 수신하고 규격에 맞는 응답을 반환합니다.
 */
@RestController
@RequestMapping("/callback")
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    /** 콜백 JWT 만료 시간 (10분) — 재생 권한 응답은 단기 유효해야 함 */
    private static final int CALLBACK_JWT_EXPIRE_SEC = 600;
    /** 콘텐츠 만료 기간 (30일, 초 단위) */
    private static final long CONTENT_EXPIRE_SEC = 60L * 60 * 24 * 30;
    /** Kollus 콜백 응답 헤더 — 커스텀 키 전달용 */
    private static final String USERKEY_HEADER = "X-KOLLUS-USERKEY";
    /** 다음 회차 콜백 기본 만료 시간 (분) */
    private static final int NEXT_EPISODE_EXPIRY_MINUTES = 3;
    /** 콜백 kind 1 — 승인 + 만료일 설정 (재생 콜백: 재생 만료 설정, DRM 콜백: 다운로드 승인) */
    private static final int KIND_GRANT_WITH_EXPIRATION = 1;
    /** 콜백 kind 2 — 다운로드 완료 통보 (DRM 콜백 전용) */
    private static final int KIND_DOWNLOAD_COMPLETED = 2;
    /** 콜백 kind 3 — 재생 권한 최종 확인 (재생 콜백: 최종 재생 승인, DRM 콜백: 오프라인 재생 권한 확인) */
    private static final int KIND_PLAYBACK_APPROVAL = 3;
    /** LMS 콜백 로그 파일 경로 */
    private static final Path LMS_LOG_FILE = Path.of("logs", "lms.log");

    private final KollusTokenService tokenService;
    private final KollusProperties properties;
    private final ObjectMapper objectMapper;

    public CallbackController(KollusTokenService tokenService,
                              KollusProperties properties,
                              ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /callback/play — 재생 콜백.
     * kind1: 재생 만료 설정(expiration_date 반환), kind3: 최종 재생 승인(result만 반환)
     *
     * @param kindParam 콜백 종류 (1: 재생 만료 설정, 3: 최종 재생 승인)
     * @return JWT 본문(text/plain) + X-KOLLUS-USERKEY 헤더, 알 수 없는 kind 면 400
     */
    @PostMapping("/play")
    public ResponseEntity<String> play(@RequestParam(value = "kind", required = false) String kindParam) {
        Integer kind = parseIntOrNull(kindParam);
        long now = Instant.now().getEpochSecond();

        if (kind != null && kind == KIND_GRANT_WITH_EXPIRATION) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("expiration_date", now + CONTENT_EXPIRE_SEC);
            data.put("result", 1);
            return callbackJwt(payloadWithExp(data, now));
        } else if (kind != null && kind == KIND_PLAYBACK_APPROVAL) {
            return callbackJwt(payloadWithExp(Map.of("result", 1), now));
        }
        return ResponseEntity.badRequest().body("Unknown kind");
    }

    /**
     * POST /callback/drm — DRM 콜백 (오프라인 콘텐츠 재생 권한 처리).
     * 요청: items 파라미터에 JSON 배열 문자열. 응답: data 배열을 JWT로 반환.
     *
     * @param itemsJson items 파라미터 (kind/media_content_key/start_at 항목의 JSON 배열 문자열)
     * @return data 배열을 담은 JWT 본문(text/plain), items 파싱 실패 시 400
     */
    @PostMapping("/drm")
    public ResponseEntity<String> drm(@RequestParam(value = "items", required = false) String itemsJson) {
        List<Map<String, Object>> items;
        try {
            items = objectMapper.readValue(itemsJson, new TypeReference<>() {
            });
        } catch (JacksonException | IllegalArgumentException e) {
            // items 파싱 실패: 잘못된 JSON 형식 또는 파라미터 누락
            return ResponseEntity.badRequest().body("Invalid items");
        }

        long now = Instant.now().getEpochSecond();
        List<Map<String, Object>> data = new ArrayList<>();

        for (Map<String, Object> item : items) {
            Integer kind = parseIntOrNull(String.valueOf(item.get("kind")));
            Object mediaContentKey = item.get("media_content_key");

            if (kind == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            if (kind == KIND_GRANT_WITH_EXPIRATION) {
                // 다운로드 승인
                entry.put("kind", KIND_GRANT_WITH_EXPIRATION);
                entry.put("media_content_key", mediaContentKey);
                entry.put("expiration_date", now + CONTENT_EXPIRE_SEC);
                entry.put("result", 1);
                data.add(entry);
            } else if (kind == KIND_DOWNLOAD_COMPLETED) {
                // 다운로드 완료 통보
                entry.put("kind", KIND_DOWNLOAD_COMPLETED);
                entry.put("media_content_key", mediaContentKey);
                entry.put("result", 1);
                data.add(entry);
            } else if (kind == KIND_PLAYBACK_APPROVAL) {
                // 오프라인 재생 권한 확인
                entry.put("kind", KIND_PLAYBACK_APPROVAL);
                entry.put("media_content_key", mediaContentKey);
                entry.put("start_at", item.get("start_at"));
                entry.put("result", 1);
                data.add(entry);
            }
        }

        return callbackJwt(Map.of("data", data));
    }

    /**
     * POST /callback/next-episode — 다음 회차 연속 재생 콜백.
     * 요청: mck(현재 콘텐츠 키), id(client_user_id).
     * 응답: JSON {@code { result, next_url }} — next_url 은 반드시 SI링크(/si) 형태.
     *
     * @param mck 현재 재생 중인 미디어 콘텐츠 키
     * @param id  시청자 식별자(client_user_id), 미지정 시 "unknown" 으로 대체
     * @return {@code {result: 1, next_url}} 또는 다음 회차가 없거나 토큰 생성 실패 시 {@code {result: 0}}
     */
    @PostMapping("/next-episode")
    public ResponseEntity<Map<String, Object>> nextEpisode(
            @RequestParam(value = "mck", required = false) String mck,
            @RequestParam(value = "id", required = false) String id) {

        String nextMck = getNextMck(mck);
        if (nextMck == null) {
            return ResponseEntity.ok(Map.of("result", 0));
        }

        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("securityKey", properties.securityKey());
            params.put("customKey", properties.customKey());
            params.put("cuid", (id != null && !id.isEmpty()) ? id : "unknown");
            params.put("mck", nextMck);
            params.put("expt", NEXT_EPISODE_EXPIRY_MINUTES);

            TokenResponse result = tokenService.generatePlayerToken(params);
            // Kollus 다음회차 콜백은 SI링크(/si) 형태를 요구함 — /s 가 아님
            String nextUrl = result.link().replace("/s?", "/si?");

            return ResponseEntity.ok(Map.of("result", 1, "next_url", nextUrl));
        } catch (RuntimeException e) {
            // 토큰 생성 실패: result 0 으로 응답하여 플레이어가 연속 재생을 중단하도록 처리
            log.error("[Kollus] Next episode callback error", e);
            return ResponseEntity.ok(Map.of("result", 0));
        }
    }

    /**
     * POST /callback/lms — LMS 콜백 (시청 이력 및 학습 진도 데이터 수신).
     *
     * <p>요청은 {@code json_data} 파라미터에 {@code user_info}/{@code content_info}/{@code block_info}
     * 구조의 JSON 문자열로 전달됩니다. 진도율(재생 시간 대비 시청 시간)과 구간 이동(트리키 클릭)
     * 여부를 요약해 로그 파일에 남깁니다 — 실제 서비스에서는 이 요약을 DB에 누적 저장하는 방식으로
     * 대체하면 됩니다. fire-and-forget: 파싱/로그 기록 성공 여부와 무관하게 즉시 200 응답합니다.
     *
     * @param jsonData json_data 파라미터 (user_info/content_info/block_info 구조의 JSON 문자열)
     * @return 항상 200 OK (본문 없음)
     */
    @PostMapping("/lms")
    public ResponseEntity<Void> lms(@RequestParam(value = "json_data", required = false) String jsonData) {
        if (jsonData == null || jsonData.isEmpty()) {
            log.warn("[Kollus] LMS callback: json_data 파라미터가 없어 무시합니다");
            return ResponseEntity.ok().build();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {
            });

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.putAll(summarizeLms(payload));

            Files.createDirectories(LMS_LOG_FILE.getParent());
            String line = objectMapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(LMS_LOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JacksonException | IllegalArgumentException | IOException e) {
            // json_data 파싱 실패 또는 로그 기록 실패: 클라이언트 응답에는 영향 없음, 서버 로그로만 기록
            log.error("[Kollus] LMS callback parse/log error", e);
        }
        return ResponseEntity.ok().build();
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────

    /**
     * LMS 콜백 payload(json_data 디코딩 결과)를 파싱해 진도율 체크에 필요한 요약 정보를 만듭니다.
     *
     * <ul>
     *   <li>시청 세트: client_user_id + media_content_key 조합으로 식별
     *       (실제 서비스에서는 이 조합 기준으로 DB에 playtime/showtime을 누적 저장)</li>
     *   <li>구간 이동(트리키 클릭) 감지: block_info의 b{n} 플래그 중 마지막으로 시청 완료된 블록
     *       이전에 미시청 구간이 남아있으면 구간을 건너뛴 것으로 판단</li>
     * </ul>
     *
     * @param payload json_data 디코딩 결과
     * @return 로그로 남길 요약 데이터
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> summarizeLms(Map<String, Object> payload) {
        Map<String, Object> userInfo = (Map<String, Object>) payload.getOrDefault("user_info", Map.of());
        Map<String, Object> contentInfo = (Map<String, Object>) payload.getOrDefault("content_info", Map.of());
        Map<String, Object> blockInfo = (Map<String, Object>) payload.getOrDefault("block_info", Map.of());
        Map<String, Object> blocks = (Map<String, Object>) blockInfo.getOrDefault("blocks", Map.of());

        BlockAnalysis analysis = analyzeBlocks(blocks);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tracking_key", userInfo.getOrDefault("client_user_id", "unknown")
                + ":" + contentInfo.getOrDefault("media_content_key", "unknown"));
        summary.put("client_user_id", userInfo.get("client_user_id"));
        summary.put("player_id", userInfo.get("player_id"));
        summary.put("device", userInfo.get("device"));
        summary.put("media_content_key", contentInfo.get("media_content_key"));
        summary.put("channel_key", contentInfo.get("channel_key"));
        summary.put("duration", asIntOrZero(contentInfo.get("duration")));
        summary.put("showtime", asIntOrZero(contentInfo.get("showtime")));
        summary.put("playtime", asIntOrZero(contentInfo.get("playtime")));
        summary.put("playtime_percent", asIntOrZero(contentInfo.get("playtime_percent")));
        summary.put("total_block_count", asIntOrZero(blockInfo.get("block_count")));
        summary.put("watched_block_count", analysis.watchedCount());
        summary.put("skip_detected", analysis.isSkipDetected());
        summary.put("uservalues", payload.getOrDefault("uservalues", Map.of()));
        return summary;
    }

    /**
     * block_info.blocks(b{n}/t{n}/p{n} 트리플)를 분석해 시청 블록 수와 구간 이동(스킵) 여부를 계산합니다.
     */
    private BlockAnalysis analyzeBlocks(Map<String, Object> blocks) {
        Pattern blockKeyPattern = Pattern.compile("^b(\\d+)$");
        Map<Integer, Boolean> watchedFlags = new TreeMap<>();

        for (Map.Entry<String, Object> entry : blocks.entrySet()) {
            Matcher matcher = blockKeyPattern.matcher(entry.getKey());
            if (matcher.matches()) {
                int index = Integer.parseInt(matcher.group(1));
                watchedFlags.put(index, "1".equals(String.valueOf(entry.getValue())));
            }
        }

        List<Integer> watchedIndexes = watchedFlags.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();
        if (watchedIndexes.isEmpty()) {
            return new BlockAnalysis(0, false);
        }

        int lastWatchedIndex = watchedIndexes.get(watchedIndexes.size() - 1);

        // 마지막 시청 블록 이전 구간에 미시청 블록이 있으면 트리키 클릭으로 구간을 건너뛴 것으로 판단
        boolean isSkipDetected = false;
        for (Map.Entry<Integer, Boolean> entry : watchedFlags.entrySet()) {
            if (entry.getKey() >= lastWatchedIndex) {
                break;
            }
            if (!entry.getValue()) {
                isSkipDetected = true;
                break;
            }
        }

        return new BlockAnalysis(watchedIndexes.size(), isSkipDetected);
    }

    /** 시청 블록 수와 구간 이동(스킵) 감지 여부. */
    private record BlockAnalysis(int watchedCount, boolean isSkipDetected) {}

    /** 숫자 또는 숫자 문자열을 정수로 변환하되 실패/미지정 시 0을 반환합니다. */
    private int asIntOrZero(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            // 숫자 파싱 실패: LMS 수치 필드(duration/playtime 등)가 비정상이면 0 으로 기록해 로그 적재를 막지 않음
            return 0;
        }
    }

    /**
     * 다음 회차 MCK를 반환합니다.
     * 샘플에서는 설정된 목록에서 랜덤 반환합니다.
     * 실제 서비스에서는 currentMck 기준으로 DB 또는 API를 조회하여 구현합니다.
     *
     * @param currentMck 현재 재생 중인 미디어 콘텐츠 키
     * @return 다음 회차 MCK, 없으면 null
     */
    private String getNextMck(String currentMck) {
        List<String> mcks = properties.nextEpisodeMckList();
        if (mcks.isEmpty()) {
            return null;
        }
        return mcks.get(ThreadLocalRandom.current().nextInt(mcks.size()));
    }

    /** data 와 만료 시간(exp)을 포함한 콜백 페이로드를 만듭니다. */
    private Map<String, Object> payloadWithExp(Map<String, Object> data, long now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", data);
        payload.put("exp", now + CALLBACK_JWT_EXPIRE_SEC);
        return payload;
    }

    /** Kollus 콜백 규격에 따라 JWT 본문과 X-KOLLUS-USERKEY 헤더를 응답합니다. */
    private ResponseEntity<String> callbackJwt(Object payload) {
        String jwt = tokenService.signCallbackJwt(payload, properties.securityKey());
        return ResponseEntity.ok()
                .header(USERKEY_HEADER, properties.customKey())
                .contentType(MediaType.TEXT_PLAIN)
                .body(jwt);
    }

    /** 문자열을 정수로 파싱하되 실패 시 null 을 반환합니다. */
    private Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // 숫자 파싱 실패: 콜백 kind 파라미터가 비정상이면 null 을 반환해 호출부가 "알 수 없는 kind" 로 처리
            return null;
        }
    }
}
