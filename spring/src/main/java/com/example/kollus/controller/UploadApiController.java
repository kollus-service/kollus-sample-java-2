package com.example.kollus.controller;

import com.example.kollus.config.KollusProperties;
import com.example.kollus.service.KollusUploadService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kollus Upload API 프록시 라우터.
 * 브라우저에서 직접 호출할 수 없는 Kollus 엔드포인트를 서버 사이드에서 중계합니다.
 */
@RestController
@RequestMapping("/api")
public class UploadApiController {

    private static final String ACCESS_TOKEN_KEY = "access_token";
    private static final String CATEGORY_KEY = "category_key";
    private static final String PROFILE_KEY = "profile_key";

    /** 업로드 URL 기본 유효 시간 (초) */
    private static final int DEFAULT_EXPIRE_TIME_SEC = 600;
    private static final int MIN_EXPIRE_TIME_SEC = 60;
    private static final int MAX_EXPIRE_TIME_SEC = 21600;

    private final KollusUploadService uploadService;
    private final KollusProperties properties;

    public UploadApiController(KollusUploadService uploadService, KollusProperties properties) {
        this.uploadService = uploadService;
        this.properties = properties;
    }

    /**
     * 업로드 URL을 발급받아 반환합니다. POST /api/upload/create-url
     *
     * <p>{@code is_passthrough} 가 참이면 인코딩 없이 원본을 그대로 저장하는 패스스루 업로드로
     * 처리하며, 이 경우 {@code profile_key}(인코딩 프로파일 키)가 필수입니다. title/expire_time 은
     * 선택값으로, 미지정 시 각각 file_name(또는 "untitled")/600초로 대체됩니다.
     *
     * @param body title/file_name/category_key/expire_time/is_audio_upload(선택),
     *             is_encryption_upload(선택, 일반 업로드 전용),
     *             is_passthrough/profile_key(선택, 패스스루 업로드 시 profile_key 필수),
     *             access_token(선택, 미지정 시 설정값)
     * @return Kollus API 원본 응답(JSON) 또는 검증 오류(400)
     */
    @PostMapping("/upload/create-url")
    public ResponseEntity<Object> createUrl(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body != null ? body : Map.of();

        boolean isPassthrough = asBoolean(params.get("is_passthrough"));
        String profileKey = asNonEmptyString(params.get(PROFILE_KEY));

        if (isPassthrough && profileKey == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "fail",
                    "error", "패스스루 업로드 시 profile_key(인코딩 프로파일 키)는 필수입니다"));
        }

        String token = isTruthy(params.get(ACCESS_TOKEN_KEY))
                ? params.get(ACCESS_TOKEN_KEY).toString() : properties.apiToken();
        String category = isTruthy(params.get(CATEGORY_KEY))
                ? params.get(CATEGORY_KEY).toString() : properties.categoryKey();
        // 제목 미입력 시 파일명을 제목으로 사용 (패스스루는 제목이 의미 없어 미입력을 허용)
        String title = asNonEmptyString(params.get("title"));
        if (title == null) {
            title = asNonEmptyString(params.get("file_name"));
        }
        if (title == null) {
            title = "untitled";
        }
        int expireTime = clamp(asInt(params.get("expire_time"), DEFAULT_EXPIRE_TIME_SEC),
                MIN_EXPIRE_TIME_SEC, MAX_EXPIRE_TIME_SEC);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("expire_time", String.valueOf(expireTime));
        form.put("title", title);
        form.put(CATEGORY_KEY, category);
        putIfTruthy(form, "is_audio_upload", params.get("is_audio_upload"));

        String response;
        if (isPassthrough) {
            form.put("is_passthrough", "1");
            form.put(PROFILE_KEY, profileKey);
            response = uploadService.createPassthroughUploadUrl(token, form);
        } else {
            putIfTruthy(form, "is_encryption_upload", params.get("is_encryption_upload"));
            response = uploadService.createUploadUrl(token, form);
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    /** 값이 null 이거나 빈 문자열이면 false 로 취급합니다. */
    private boolean isTruthy(Object value) {
        return value != null && !value.toString().isEmpty();
    }

    /** "1"/"true"(대소문자 무관)/Boolean.TRUE 를 참으로 취급합니다. */
    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        String s = value.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /** 값이 없거나 공백뿐이면 null, 아니면 트리밍된 문자열을 반환합니다. */
    private String asNonEmptyString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 정수로 파싱하되 실패/미지정이면 기본값을 반환합니다. */
    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            // 숫자 파싱 실패: expire_time 등 선택 파라미터가 비정상이면 기본값으로 처리해 업로드 URL 발급을 막지 않음
            return defaultValue;
        }
    }

    /** 값을 [min, max] 범위로 자릅니다. */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 값이 존재하고 비어있지 않으면 문자열로 변환해 담습니다. */
    private void putIfTruthy(Map<String, String> target, String key, Object value) {
        if (isTruthy(value)) {
            target.put(key, value.toString());
        }
    }
}
