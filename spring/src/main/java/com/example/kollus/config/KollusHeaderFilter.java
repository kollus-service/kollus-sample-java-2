package com.example.kollus.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Kollus 플레이어 iframe 권한 위임 및 Private Network Access(PNA) 허용 헤더를 부여합니다.
 *
 * <p>Chrome 의 PNA 정책상 OPTIONS preflight 요청에 {@code Access-Control-Allow-Private-Network}
 * 응답 헤더가 없으면 iframe 로드가 차단됩니다.
 */
@Component
@Order(0)
public class KollusHeaderFilter extends OncePerRequestFilter {

    private static final String PERMISSIONS_POLICY =
            "local-network-access=*, encrypted-media=*, autoplay=*, fullscreen=*";
    private static final String HTTP_METHOD_OPTIONS = "OPTIONS";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Private-Network", "true");
        response.setHeader("Access-Control-Allow-Headers", "Access-Control-Request-Private-Network");

        // PNA preflight 요청은 본문 없이 204 로 즉시 응답
        if (HTTP_METHOD_OPTIONS.equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
