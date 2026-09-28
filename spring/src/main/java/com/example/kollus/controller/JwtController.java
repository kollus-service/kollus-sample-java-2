package com.example.kollus.controller;

import com.example.kollus.config.KollusProperties;
import com.example.kollus.dto.TokenResponse;
import com.example.kollus.service.KollusTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kollus 플레이어 JWT 발급 라우터.
 * GET /jwt/create(쿼리 파라미터) 또는 POST /jwt/create(JSON 바디)로 토큰을 발급합니다.
 */
@RestController
@RequestMapping("/jwt")
public class JwtController {

    private final KollusTokenService tokenService;
    private final KollusProperties properties;

    public JwtController(KollusTokenService tokenService, KollusProperties properties) {
        this.tokenService = tokenService;
        this.properties = properties;
    }

    /**
     * GET /jwt/create — 쿼리 파라미터로 토큰 발급
     *
     * @param params 쿼리 파라미터 (mck 필수, secretKey/customKey 미지정 시 서버 설정값 사용)
     * @return 토큰/링크/페이로드 또는 검증 오류(400)
     */
    @GetMapping("/create")
    public ResponseEntity<Object> createGet(@RequestParam Map<String, String> params) {
        return createJwt(new LinkedHashMap<>(params));
    }

    /**
     * POST /jwt/create — JSON 바디로 토큰 발급
     *
     * @param body JSON 요청 바디 (mck 필수, secretKey/customKey 미지정 시 서버 설정값 사용; null 이면 빈 파라미터로 처리)
     * @return 토큰/링크/페이로드 또는 검증 오류(400)
     */
    @PostMapping("/create")
    public ResponseEntity<Object> createPost(@RequestBody(required = false) Map<String, Object> body) {
        return createJwt(body != null ? new LinkedHashMap<>(body) : new LinkedHashMap<>());
    }

    /**
     * JWT 생성 공통 로직 — 요청 파라미터를 받아 토큰을 발급합니다.
     *
     * @param params 요청 파라미터 (쿼리 또는 JSON 바디)
     * @return 토큰/링크/페이로드 또는 검증 오류(400)
     */
    private ResponseEntity<Object> createJwt(Map<String, Object> params) {
        // 필수 파라미터 검증
        if (!isTruthy(params.get("mck"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required parameters: mck"));
        }

        // secretKey/customKey 는 요청값이 비어 있으면 서버 기본 설정값 사용
        params.put("secretKey", isTruthy(params.get("secretKey"))
                ? params.get("secretKey") : properties.secretKey());
        params.put("customKey", isTruthy(params.get("customKey"))
                ? params.get("customKey") : properties.customKey());

        TokenResponse result = tokenService.generatePlayerToken(params);
        return ResponseEntity.ok(result);
    }

    /** 값이 null 이거나 빈 문자열이면 false 로 취급합니다. */
    private boolean isTruthy(Object value) {
        return value != null && !value.toString().isEmpty();
    }
}
