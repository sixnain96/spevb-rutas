package co.edu.usta.spevb.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La clave del NAVEGADOR (mapa base de Google) es pública por diseño pero solo se entrega si tiene un formato plausible; vacía = mapa de respaldo. */
class MapaConfigControllerTest {
    private MockMvc mvc(String clave) { return MockMvcBuilders.standaloneSetup(new MapaConfigController(clave, "e79ad4f6c17327dac3b595bc")).build(); }

    @Test void conClaveConFormatoValidoLaEntregaYDiceGoogle() throws Exception {
        mvc("AIzaSyFALSA_FALSA_FALSA_FALSA_12345").perform(get("/api/mapa/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.googleMapsKey").value("AIzaSyFALSA_FALSA_FALSA_FALSA_12345")).andExpect(jsonPath("$.mapId").value("e79ad4f6c17327dac3b595bc")).andExpect(jsonPath("$.proveedor").value("google"));
    }

    @Test void sinClaveOConUnaRaraNoEntregaNadaYUsaElRespaldo() throws Exception {
        for (String c : new String[] { "", "   ", "corta", "con espacios y símbolos <script>alert(1)</script>" })
            mvc(c).perform(get("/api/mapa/config")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.googleMapsKey").value("")).andExpect(jsonPath("$.proveedor").value("respaldo"));
    }
}
