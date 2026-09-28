package com.example.kollus.controller;

import com.example.kollus.config.KollusProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 메인 페이지 및 샘플 UI 라우터.
 * 홈(/), 샘플 플레이어(/sample), 파일 업로드(/upload) 페이지를 제공합니다.
 */
@Controller
public class PageController {

    private final KollusProperties properties;

    public PageController(KollusProperties properties) {
        this.properties = properties;
    }

    /**
     * GET / — 홈 페이지
     *
     * @param model 뷰 모델 (페이지 제목 전달)
     * @return 뷰 이름 {@code index}
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("title", "Kollus Java(Spring) Sample");
        return "index";
    }

    /**
     * GET /sample — JWT 발급 + 플레이어 샘플 UI
     *
     * @param model 뷰 모델 (기본 Secret Key/Custom Key/MCK 전달)
     * @return 뷰 이름 {@code sample}
     */
    @GetMapping("/sample")
    public String sample(Model model) {
        model.addAttribute("title", "Kollus Player Sample");
        model.addAttribute("defaultSecretKey", properties.secretKey());
        model.addAttribute("defaultCustomKey", properties.customKey());
        model.addAttribute("defaultMck", properties.sampleMck());
        return "sample";
    }

    /**
     * GET /upload — 파일 업로드 UI
     *
     * @param model 뷰 모델 (기본 API 토큰/카테고리 키 전달)
     * @return 뷰 이름 {@code upload}
     */
    @GetMapping("/upload")
    public String upload(Model model) {
        model.addAttribute("title", "Kollus 파일 업로드");
        model.addAttribute("defaultApiToken", properties.apiToken());
        model.addAttribute("defaultCategoryKey", properties.categoryKey());
        return "upload";
    }
}
