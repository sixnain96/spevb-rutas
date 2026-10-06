package co.edu.usta.spevb.config;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
@Component
public class EditorSecurityFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final byte[] token;
    public EditorSecurityFilter(@Value("${spevb.editor.enabled:false}") boolean enabled,
                                @Value("${spevb.editor.token:}") String token) {
        if (enabled && (token.length() < 32 || token.isBlank())) {
            throw new IllegalArgumentException("EDITOR_TOKEN debe contener al menos 32 caracteres para habilitar el editor.");
        }
        this.enabled = enabled;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(self)");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org; " +
                "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        if (request.getRequestURI().contains(";") || request.getRequestURI().contains("%") || request.getRequestURI().contains("\\")) {
            reject(response, 400, "La ruta de la solicitud no es válida."); return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        if (path.startsWith("/api/editor/")) {
            if (!enabled) { reject(response, 403, "El editor está deshabilitado en el servidor."); return; }
            String supplied = request.getHeader("X-Editor-Token");
            if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
                reject(response, 401, "Clave de edición ausente o incorrecta."); return;
            }
            if (!Set.of("GET", "HEAD").contains(request.getMethod())) {
                if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                    reject(response, 403, "No se permiten operaciones desde otro sitio."); return;
                }
                if (Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
                    String contentType = request.getContentType();
                    if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].strip())) {
                        reject(response, 415, "Usa Content-Type application/json."); return;
                    }
                    if (request.getContentLengthLong() > 2_000_000) {
                        reject(response, 413, "La solicitud excede el límite de 2 MB."); return;
                    }
                    byte[] body = request.getInputStream().readNBytes(2_000_001);
                    if (body.length > 2_000_000) {
                        reject(response, 413, "La solicitud excede el límite de 2 MB."); return;
                    }
                    request = new BoundedRequest(request, body);
                }
            }
        }
        if (path.startsWith("/api/") && !path.startsWith("/api/editor/") && "POST".equals(request.getMethod())) {
            String contentType = request.getContentType();
            if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";",2)[0].strip())) {
                reject(response,415,"Usa Content-Type application/json."); return;
            }
            byte[] body = request.getInputStream().readNBytes(2_000_001);
            if (body.length > 2_000_000) { reject(response,413,"La solicitud excede el límite de 2 MB."); return; }
            request = new BoundedRequest(request,body);
        }
        chain.doFilter(request, response);
    }
    private static class BoundedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BoundedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON endpoint"); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
