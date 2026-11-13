package lk.wms.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP and user agent, trimmed to the column sizes. */
public final class RequestInfo {

    private RequestInfo() {
    }

    /**
     * Remote address as seen by Tomcat. Behind Nginx, set server.forward-headers-strategy=native
     * so this is the real client IP (never parse X-Forwarded-For by hand: clients can forge it).
     */
    public static String ip(HttpServletRequest request) {
        return truncate(request.getRemoteAddr(), 45);
    }

    public static String userAgent(HttpServletRequest request) {
        return truncate(request.getHeader("User-Agent"), 255);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
