package com.ssn.hrms.common;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ssn.hrms.cluster.NodeStats;
import com.ssn.hrms.config.NodeOnly;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@NodeOnly
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessions;
    private final NodeStats stats;

    public AuthInterceptor(SessionService sessions, NodeStats stats) {
        this.sessions = sessions;
        this.stats = stats;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        stats.request();
        String path = request.getRequestURI();
        if (path.equals("/api/auth/login") || path.startsWith("/api/public/")) {
            return true;
        }
        CurrentUser user = sessions.get(SessionService.bearer(request));
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Not logged in or session expired\"}");
            return false;
        }
        request.setAttribute("user", user);
        return true;
    }
}
