package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorSecurityFilterTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private MockHttpServletRequest request() {
        var r = new MockHttpServletRequest("PUT","/api/editor/rutas/1/recorrido");
        r.setContentType("application/json"); return r;
    }
    @Test void disabledByDefault() throws Exception {
        var response = new MockHttpServletResponse();
        new EditorSecurityFilter(false,"").doFilter(request(),response,new MockFilterChain());
        assertEquals(403,response.getStatus());
    }
    @Test void refusesWeakKey() { assertThrows(IllegalArgumentException.class, () -> new EditorSecurityFilter(true,"short")); }
    @Test void rejectsMissingKey() throws Exception {
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new EditorSecurityFilter(true,KEY).doFilter(request(),response,chain);
        assertEquals(401,response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void acceptsCorrectKey() throws Exception {
        var request = request(); request.addHeader("X-Editor-Token",KEY);
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new EditorSecurityFilter(true,KEY).doFilter(request,response,chain);
        assertNotNull(chain.getRequest()); assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
    }
    @Test void rejectsWrongKey() throws Exception {
        var request = request(); request.addHeader("X-Editor-Token",KEY + "x");
        var response = new MockHttpServletResponse();
        new EditorSecurityFilter(true,KEY).doFilter(request,response,new MockFilterChain());
        assertEquals(401,response.getStatus());
    }
    @Test void rejectsCrossSiteWrite() throws Exception {
        var request = request(); request.addHeader("X-Editor-Token",KEY); request.addHeader("Sec-Fetch-Site","cross-site");
        var response = new MockHttpServletResponse();
        new EditorSecurityFilter(true,KEY).doFilter(request,response,new MockFilterChain());
        assertEquals(403,response.getStatus());
    }
    @Test void publicViewerRemainsAccessible() throws Exception {
        var request = new MockHttpServletRequest("GET","/api/rutas"); var chain = new MockFilterChain();
        new EditorSecurityFilter(false,"").doFilter(request,new MockHttpServletResponse(),chain);
        assertNotNull(chain.getRequest());
    }
    @Test void rejectsPathParameterBypass() throws Exception {
        var request = new MockHttpServletRequest("PUT","/api;param/editor/rutas/1/recorrido");
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new EditorSecurityFilter(false,"").doFilter(request,response,chain);
        assertEquals(400,response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void protectsEditorUnderContextPath() throws Exception {
        var request=new MockHttpServletRequest("PUT","/spevb/api/editor/rutas/1/recorrido"); request.setContextPath("/spevb");
        var response=new MockHttpServletResponse();
        new EditorSecurityFilter(false,"").doFilter(request,response,new MockFilterChain());
        assertEquals(403,response.getStatus());
    }
    @Test void rejectsOversizedBody() throws Exception {
        var request = request(); request.addHeader("X-Editor-Token",KEY); request.setContent(new byte[2_000_001]);
        var response = new MockHttpServletResponse();
        new EditorSecurityFilter(true,KEY).doFilter(request,response,new MockFilterChain());
        assertEquals(413,response.getStatus());
    }
    @Test void boundedBodyIsStillReadableByController() throws Exception {
        var request = request(); request.addHeader("X-Editor-Token",KEY); request.setContent("{\"test\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var chain = new MockFilterChain();
        new EditorSecurityFilter(true,KEY).doFilter(request,new MockHttpServletResponse(),chain);
        assertEquals("{\"test\":true}",new String(chain.getRequest().getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void rejectsNonJsonWrite() throws Exception {
        var request = request(); request.addHeader("X-Editor-Token",KEY); request.setContentType("text/plain");
        var response = new MockHttpServletResponse();
        new EditorSecurityFilter(true,KEY).doFilter(request,response,new MockFilterChain());
        assertEquals(415,response.getStatus());
    }
}
