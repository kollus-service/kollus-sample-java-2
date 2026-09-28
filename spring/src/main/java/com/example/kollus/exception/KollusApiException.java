package com.example.kollus.exception;

/**
 * Kollus API 호출 실패 시 발생하는 예외.
 *
 * <p>원격 API 가 반환한 HTTP 상태 코드와 응답 본문을 함께 담아 상위 계층에서
 * 클라이언트에 그대로 전달할 수 있도록 합니다.
 */
public class KollusApiException extends RuntimeException {

    /** Kollus API 가 반환한 HTTP 상태 코드 */
    private final int statusCode;
    /** Kollus API 가 반환한 원본 응답 본문 (JSON 문자열 등) */
    private final transient Object responseBody;

    /**
     * @param statusCode   원격 API HTTP 상태 코드
     * @param responseBody 원격 API 응답 본문
     * @param message      예외 메시지
     */
    public KollusApiException(int statusCode, Object responseBody, String message) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Kollus API 가 반환한 HTTP 상태 코드를 반환합니다.
     *
     * @return 원격 API HTTP 상태 코드
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Kollus API 가 반환한 원본 응답 본문을 반환합니다.
     *
     * @return 원격 API 응답 본문 (JSON 문자열 등, 없으면 null)
     */
    public Object getResponseBody() {
        return responseBody;
    }
}
