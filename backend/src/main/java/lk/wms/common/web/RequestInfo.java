package lk.wms.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Client IP and user agent, trimmed to the column sizes. */
public final class RequestInfo {

    private RequestInfo() {
    }

    /**
     * Client IP as Tomcat sees it. Behind Nginx, Tomcat's RemoteIpValve takes it from X-Forwarded-For,
     * but only when the request comes from the local proxy (server.tomcat.remoteip.internal-proxies),
     * so a client cannot forge it. Never parse X-Forwarded-For by hand.
     */
    public static String ip(HttpServletRequest request) {
        return truncate(request.getRemoteAddr(), 45);
    }

    /**
     * The key used to count one client for rate limits and the login block (F7). An IPv6 user usually
     * owns a whole /64 network and could rotate through billions of addresses, so IPv6 counts per /64.
     * IPv4 (and IPv4-mapped IPv6) counts per address.
     */
    public static String clientKey(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            InetAddress address = InetAddress.getByName(ip); // an IP literal: no DNS lookup
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            byte[] b = address.getAddress();
            return String.format("%x:%x:%x:%x::/64", ((b[0] & 0xff) << 8) | (b[1] & 0xff), ((b[2] & 0xff) << 8) | (b[3] & 0xff),
                    ((b[4] & 0xff) << 8) | (b[5] & 0xff), ((b[6] & 0xff) << 8) | (b[7] & 0xff));
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    public static String userAgent(HttpServletRequest request) {
        return truncate(request.getHeader("User-Agent"), 255);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
