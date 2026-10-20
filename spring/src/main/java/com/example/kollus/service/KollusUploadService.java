package com.example.kollus.service;

import com.example.kollus.exception.KollusApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Kollus Upload API 프록시 서비스.
 *
 * <p>브라우저에서 직접 호출할 수 없는 Kollus {@code c-api-kr} / 패스스루 업로드 엔드포인트를
 * 서버 사이드에서 중계합니다.
 */
@Service
public class KollusUploadService {

    private static final String ACCESS_TOKEN_PARAM = "access_token";

    private final RestClient kollusUploadRestClient;
    private final RestClient kollusUploadPassthroughRestClient;

    public KollusUploadService(@Qualifier("kollusUploadRestClient") RestClient kollusUploadRestClient,
                                @Qualifier("kollusUploadPassthroughRestClient") RestClient kollusUploadPassthroughRestClient) {
        this.kollusUploadRestClient = kollusUploadRestClient;
        this.kollusUploadPassthroughRestClient = kollusUploadPassthroughRestClient;
    }

    /**
     * Kollus 업로드 URL을 발급받아 원본 응답(JSON)을 그대로 반환합니다.
     *
     * @param accessToken Kollus API 액세스 토큰
     * @param formParams  Kollus 로 전달할 폼 파라미터 (expire_time, title, category_key 등)
     * @return Kollus API 원본 응답 본문 (JSON 문자열)
     * @throws KollusApiException API 호출 실패 시
     */
    public String createUploadUrl(String accessToken, Map<String, String> formParams) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        formParams.forEach(body::add);

        try {
            // access_token 은 URI 변수로 전달해 예약 문자(&, =, + 등)까지 URL 인코딩
            return kollusUploadRestClient.post()
                    .uri(builder -> builder.path("/upload/create-url")
                            .queryParam(ACCESS_TOKEN_PARAM, "{accessToken}")
                            .build(accessToken))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Kollus API 가 4xx/5xx 를 반환: 상태코드와 응답 본문을 담아 상위로 전달
            throw new KollusApiException(
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    "Kollus Upload API create-url 실패");
        }
    }

    /**
     * 패스스루(passthrough) 업로드 URL을 발급받아 원본 응답(JSON)을 그대로 반환합니다.
     *
     * <p>인코딩 없이 원본 파일을 그대로 저장하는 업로드 방식으로, 일반 업로드와 다른
     * 전용 엔드포인트({@code upload.kr.kollus.com})를 사용하며 응답 포맷도 다릅니다
     * ({@code {error, message, result}} — 일반 업로드의 {@code {status, data}} 와 다름).
     *
     * @param accessToken Kollus API 액세스 토큰
     * @param formParams  Kollus 로 전달할 폼 파라미터 (expire_time, title, category_key, profile_key 등)
     * @return Kollus API 원본 응답 본문 (JSON 문자열)
     * @throws KollusApiException API 호출 실패 시
     */
    public String createPassthroughUploadUrl(String accessToken, Map<String, String> formParams) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        formParams.forEach(body::add);

        try {
            // access_token 은 URI 변수로 전달해 예약 문자(&, =, + 등)까지 URL 인코딩
            return kollusUploadPassthroughRestClient.post()
                    .uri(builder -> builder.path("/create_url")
                            .queryParam(ACCESS_TOKEN_PARAM, "{accessToken}")
                            .build(accessToken))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Kollus API 가 4xx/5xx 를 반환: 상태코드와 응답 본문을 담아 상위로 전달
            throw new KollusApiException(
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    "Kollus Upload API create_url(passthrough) 실패");
        }
    }

}
