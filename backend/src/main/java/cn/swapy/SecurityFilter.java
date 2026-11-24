package cn.swapy;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.*;

@Component
public final class SecurityFilter extends OncePerRequestFilter {
    public final String token=UUID.randomUUID().toString();
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String host=request.getServerName();boolean rejected=!Set.of("127.0.0.1","localhost","[::1]","::1").contains(host);
        String origin=request.getHeader("Origin");
        if(origin!=null)try {URI uri=URI.create(origin);int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();rejected|=!Objects.equals(uri.getHost(),host)||port!=request.getServerPort()||!uri.getScheme().equals(request.getScheme());}catch(Exception e){rejected=true;}
        boolean changes=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        if(request.getRequestURI().startsWith("/api/") && changes && !token.equals(request.getHeader("X-Swapy-Token")))rejected=true;
        if(rejected){response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(Json.write(Map.of("code","ORIGIN_OR_TOKEN_REJECTED","message","请从本机Swapy页面发起操作")));return;}
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");response.setHeader("Referrer-Policy","same-origin");
        if(request.getRequestURI().startsWith("/api/"))response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);
    }
}
