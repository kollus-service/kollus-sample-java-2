package com.example.kollus.controller;

import com.example.kollus.service.KollusMultiDrmService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 멀티 DRM(Widevine/PlayReady/FairPlay) 플레이어 라우터.
 *
 * <ul>
 *   <li>{@code GET /multidrm} — 미디어 콘텐츠 키/콘텐츠 ID 입력 폼 페이지</li>
 *   <li>{@code GET /player/multi-drm} — 서버가 DRM 플레이어 URL 을 생성해 iframe 플레이어 렌더링</li>
 * </ul>
 *
 * <p>DRM 종류/스트리밍 방식은 요청 User-Agent 로 자동 선택되므로, 폼 페이지는 이 라우트를
 * 브라우저 iframe 으로 직접 로드해 실제 브라우저의 User-Agent 가 전달되도록 합니다.
 */
@Controller
public class MultiDrmController {

    private static final Logger log = LoggerFactory.getLogger(MultiDrmController.class);

    private final KollusMultiDrmService multiDrmService;

    public MultiDrmController(KollusMultiDrmService multiDrmService) {
        this.multiDrmService = multiDrmService;
    }

    /**
     * GET /multidrm — 멀티 DRM 재생 입력 폼 페이지
     *
     * @param model 뷰 모델 (페이지 제목 전달)
     * @return 뷰 이름 {@code multidrm}
     */
    @GetMapping("/multidrm")
    public String form(Model model) {
        model.addAttribute("title", "Kollus 멀티 DRM 플레이어");
        return "multidrm";
    }

    /**
     * GET /player/multi-drm — 멀티 DRM 플레이어 페이지를 렌더링합니다.
     *
     * @param mediaContentKey 재생할 미디어 콘텐츠 키 (필수)
     * @param contentId       멀티 DRM 콘텐츠 ID / 업로드 파일 키 (필수)
     * @param cuid            시청자 ID (선택, 미지정 시 guest)
     * @param userAgent       DRM/스트리밍 방식 판별용 요청 User-Agent
     * @param model           뷰 모델 (성공 시 playerUrl, 실패 시 status/message/error 전달)
     * @param response        실패 시 HTTP 상태 코드를 설정할 응답 객체
     * @return 성공 시 뷰 이름 {@code player}, 실패 시 {@code error}
     */
    @GetMapping("/player/multi-drm")
    public String player(@RequestParam("media_content_key") String mediaContentKey,
                         @RequestParam("content_id") String contentId,
                         @RequestParam(value = "cuid", required = false) String cuid,
                         @RequestHeader(value = "User-Agent", required = false) String userAgent,
                         Model model,
                         HttpServletResponse response) {
        // 실제 서비스에서는 auth 로 보호하고 인증된 사용자 ID 를 사용할 것
        String clientUserId = (cuid != null && !cuid.isEmpty()) ? cuid : "guest";

        try {
            String playerUrl = multiDrmService.buildPlayerUrl(
                    clientUserId, mediaContentKey, contentId, userAgent != null ? userAgent : "");
            model.addAttribute("playerUrl", playerUrl);
            return "player";
        } catch (RuntimeException e) {
            // JWT 생성/암호화/설정 누락은 서버 설정 문제이므로 500 으로 에러 페이지 렌더링
            log.error("[Kollus] 멀티 DRM 플레이어 URL 생성 실패", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            model.addAttribute("status", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            model.addAttribute("message", "Kollus 멀티 DRM 플레이어 URL 생성에 실패했습니다.");
            model.addAttribute("error", e.getMessage());
            return "error";
        }
    }
}