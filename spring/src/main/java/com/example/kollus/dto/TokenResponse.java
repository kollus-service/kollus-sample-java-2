package com.example.kollus.dto;

import java.util.Map;

/**
 * JWT 발급 결과.
 *
 * @param token   서명된 JWT 문자열
 * @param link    플레이어 재생 URL (jwt, custom_key 쿼리 포함)
 * @param payload JWT 페이로드 (디버깅/표시용)
 */
public record TokenResponse(
        String token,
        String link,
        Map<String, Object> payload
) {
}
