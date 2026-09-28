package com.example.kollus.exception;

import com.example.kollus.controller.CallbackController;
import com.example.kollus.controller.JwtController;
import com.example.kollus.controller.UploadApiController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * API 컨트롤러 공통 예외 처리기.
 *
 * <p>페이지 렌더링 컨트롤러({@link com.example.kollus.controller.PageController})는 제외하고
 * JSON 을 반환하는 API 컨트롤러에만 적용합니다.
 */
@RestControllerAdvice(assignableTypes = {
        JwtController.class,
        UploadApiController.class,
        CallbackController.class
})
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Kollus API 호출 실패 처리 — 원격 상태 코드와 응답 본문을 그대로 전달합니다.
     *
     * @param e Kollus API 호출 실패 예외 (원격 상태 코드/응답 본문 포함)
     * @return 원격 API 상태 코드 그대로, {@code {status: "fail", error: 원격 응답}} 본문
     */
    @ExceptionHandler(KollusApiException.class)
    public ResponseEntity<Map<String, Object>> handleKollusApi(KollusApiException e) {
        // Kollus API 에러: 상태코드와 메시지를 로깅하고 클라이언트에 오류 반환
        log.error("[Kollus] API Error [{}]: {}", e.getStatusCode(), e.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "fail");
        body.put("error", parseBodyOrRaw(e.getResponseBody()));
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    /**
     * 예상치 못한 오류 처리 — 500(Internal Server Error) 으로 응답합니다.
     *
     * @param e 처리되지 않은 예외
     * @return 500 상태, {@code {status: "fail", error: 예외 메시지}} 본문
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        // 예상치 못한 에러: 서버 로그에 스택트레이스 기록
        log.error("[Kollus] Unexpected error", e);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "fail");
        body.put("error", e.getMessage());
        return ResponseEntity.internalServerError().body(body);
    }

    /**
     * 원격 응답 본문이 JSON 이면 객체로 파싱하고, 아니면 원문 문자열을 그대로 반환합니다.
     */
    private Object parseBodyOrRaw(Object responseBody) {
        if (!(responseBody instanceof String raw) || raw.isEmpty()) {
            return responseBody;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception ignored) {
            // JSON 이 아니면 원문 그대로 사용
            return raw;
        }
    }
}
