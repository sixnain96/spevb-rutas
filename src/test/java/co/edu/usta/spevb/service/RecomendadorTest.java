package co.edu.usta.spevb.service;

import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.service.Recomendador.Candidato;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Política de recomendación (PoliticaRecomendacion.DEFECTO, versión 2). Los números de estos escenarios son SINTÉTICOS: comprueban la intención
 * de la regla (priorizar el acceso al primer bus sin perjuicio desproporcionado), no son rutas reales de Villavicencio.
 */
class RecomendadorTest {
    private static final PoliticaRecomendacion P = PoliticaRecomendacion.DEFECTO;

    private static Candidato directa(String id, double ini, double fin, double min) {
        return new Candidato(id, "DIRECTA", true, null, ini, 0, fin, min, 1, "CALLES");
    }

    private static Candidato transbordo(String id, double ini, double cambio, double fin, double min) {
        return new Candidato(id, "TRANSBORDO", true, null, ini, cambio, fin, min, 2, "CALLES");
    }

    private static Candidato contraria(String id, double ini, double fin, double min) {
        return new Candidato(id, "SENTIDO_CONTRARIO", false, "Va en sentido contrario al registrado para la ruta.", ini, 0, fin, min, 1, "CALLES");
    }

    private static ViajeResponse.Item item(ViajeResponse.Recomendacion r, String id) {
        return r.ranking().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- Caso A: el acceso al primer bus justifica el transbordo, y la directa se conserva
    @Test void casoA_unTransbordoConAccesoMuchoMasCercanoEsLaRecomendadaYLaDirectaSeConserva() {
        var r = Recomendador.recomendar(P, List.of(directa("D:1", 900, 100, 30), transbordo("T:2>3", 100, 100, 100, 34)));
        assertEquals("T:2>3", r.idRecomendada());
        assertEquals(List.of("T:2>3", "D:1"), r.ranking().stream().map(ViajeResponse.Item::id).toList(), "la directa sigue en el ranking");
        assertTrue(item(r, "D:1").elegible(), "la directa sigue siendo elegible como alternativa");
        assertEquals(1, item(r, "T:2>3").orden());
        assertEquals(300, item(r, "T:2>3").caminataTotalMetros(), 1e-9);
        assertTrue(r.motivo().contains("100 m") && r.motivo().contains("900 m") && r.motivo().contains("transbordo"), r.motivo());
        assertEquals(PoliticaRecomendacion.VERSION, r.politicaVersion());
        assertTrue(item(r, "T:2>3").etiquetas().containsAll(List.of("PRIMER_BUS_MAS_CERCANO", "MENOR_CAMINATA_TOTAL")));
        assertTrue(item(r, "D:1").etiquetas().contains("SIN_TRANSBORDO"));
        assertTrue(item(r, "D:1").etiquetas().contains("MENOR_TIEMPO"), "la directa es 4 min más rápida: etiqueta porque se comparó de verdad");
    }

    // ---- Caso B: 20 m menos no justifican el perjuicio
    @Test void casoB_unaMejoraDeAccesoMinimaNoJustificaCambiarDeBus() {
        var r = Recomendador.recomendar(P, List.of(directa("D:1", 150, 100, 25), transbordo("T:2>3", 130, 250, 100, 40)));
        assertEquals("D:1", r.idRecomendada());
        assertFalse(item(r, "D:1").etiquetas().contains("PRIMER_BUS_MAS_CERCANO") && item(r, "T:2>3").etiquetas().contains("PRIMER_BUS_MAS_CERCANO"));
        assertTrue(r.motivo().contains("Sin transbordo") && r.motivo().contains("150 m"), r.motivo());
    }

    // ---- Caso C: no se recomienda ciegamente el transbordo solo por la primera caminata
    @Test void casoC_unTransbordoConCaminataDeCambioYTotalDesproporcionadosNoGana() {
        var r = Recomendador.recomendar(P, List.of(directa("D:1", 800, 100, 30), transbordo("T:2>3", 100, 1800, 500, 65)));
        assertEquals("D:1", r.idRecomendada(), "ni la caminata total (2.400 m vs 900 m), ni el cambio (1.800 m), ni el tiempo (+35 min) lo justifican");
        // cada condición por separado
        assertEquals("D:1", Recomendador.recomendar(P, List.of(directa("D:1", 800, 100, 30), transbordo("T:2>3", 100, 1800, 100, 33))).idRecomendada(), "solo el cambio largo");
        assertEquals("D:1", Recomendador.recomendar(P, List.of(directa("D:1", 800, 100, 30), transbordo("T:2>3", 100, 100, 100, 60))).idRecomendada(), "solo el tiempo");
        assertEquals("D:1", Recomendador.recomendar(P, List.of(directa("D:1", 800, 100, 30), transbordo("T:2>3", 100, 300, 900, 35))).idRecomendada(), "solo la caminata total (1.300 m > 900 + 300)");
    }

    // ---- Caso D: se compara por acceso peatonal REAL, y se distingue la calidad de la distancia
    @Test void casoD_laCaminataRealPorCallesDecideNoLaDistanciaEnLineaRecta() {
        // La vía está a 60 m en línea recta, pero llegar a pie son 1.200 m por una barrera: la directa entra con 1.200 m. La otra se aborda tras 250 m reales.
        var r = Recomendador.recomendar(P, List.of(directa("D:1", 1200, 100, 30), transbordo("T:2>3", 250, 100, 100, 36)));
        assertEquals("T:2>3", r.idRecomendada());
        assertEquals(1200, item(r, "D:1").caminataInicialMetros(), 1e-9);
        // la calidad de cada cifra se informa: la estimada en línea recta NO se presenta como caminata por calles
        var recta = new Candidato("D:9", "DIRECTA", true, null, 400, 0, 100, 30, 1, "RECTA");
        var r2 = Recomendador.recomendar(P, List.of(recta, transbordo("T:2>3", 380, 100, 100, 34)));
        assertEquals("RECTA", item(r2, "D:9").calidadDistancia());
        assertEquals("CALLES", item(r2, "T:2>3").calidadDistancia());
    }

    // ---- Caso E: el sentido contrario nunca desplaza a un transbordo viable
    @Test void casoE_laAlternativaContrariaNoDesplazaAUnTransbordoViable() {
        var r = Recomendador.recomendar(P, List.of(contraria("A:1", 50, 50, 10), transbordo("T:2>3", 400, 100, 200, 40)));
        assertEquals("T:2>3", r.idRecomendada());
        assertFalse(item(r, "A:1").elegible());
        assertNotNull(item(r, "A:1").motivoNoElegible());
        assertEquals("A:1", r.ranking().get(1).id(), "la contraria queda al final del ranking (no se borra)");
        // con una directa viable, tampoco
        assertEquals("D:1", Recomendador.recomendar(P, List.of(contraria("A:1", 0, 0, 1), directa("D:1", 500, 100, 30))).idRecomendada());
    }

    // ---- Caso F: empates, orden estable entre ejecuciones y con listas barajadas
    @Test void casoF_dosResultadosEquivalentesSeOrdenanSiempreIgual() {
        var base = new ArrayList<>(List.of(directa("D:7:MENOS_CAMINATA", 300, 100, 25), directa("D:3:MENOS_CAMINATA", 300, 100, 25),
                transbordo("T:5>6", 250, 100, 100, 30), transbordo("T:4>6", 250, 100, 100, 30), contraria("A:9", 10, 10, 5),
                new Candidato("D:3:MAS_RAPIDA", "DIRECTA_MAS_RAPIDA", true, null, 400, 0, 100, 22, 1, "CALLES")));
        String esperado = null;
        var azar = new Random(42);
        for (int i = 0; i < 50; i++) {
            var lista = new ArrayList<>(base);
            Collections.shuffle(lista, azar);
            var r = Recomendador.recomendar(P, lista);
            String huella = r.idRecomendada() + "|" + r.ranking().stream().map(it -> it.id() + ":" + it.orden()).toList() + "|" + r.motivo();
            if (esperado == null) esperado = huella;
            assertEquals(esperado, huella, "iteración " + i);
        }
        assertTrue(esperado.startsWith("D:3:MENOS_CAMINATA|"), "empate exacto: decide el id: " + esperado);
    }

    // ---- Viajes sin directa, sin transbordo válido, circulares
    @Test void sinDirectaSeRecomiendaElMejorTransbordoPorAccesoYLuegoDesempate() {
        var r = Recomendador.recomendar(P, List.of(transbordo("T:1>2", 500, 100, 100, 30), transbordo("T:3>4", 200, 300, 100, 38), transbordo("T:5>6", 200, 100, 400, 33)));
        assertEquals("T:3>4", r.idRecomendada(), "menor caminata inicial (200 m, empate con T:5>6); con empate, menor caminata total (600 m vs 700 m)");
        assertTrue(r.motivo().contains("no hay una ruta sin transbordo"), r.motivo());
    }

    @Test void sinTransbordoValidoYSinDirectaNoSeRecomiendaNada() {
        var r = Recomendador.recomendar(P, List.of(contraria("A:1", 100, 100, 20)));
        assertNull(r.idRecomendada());
        assertEquals(1, r.ranking().size(), "la alternativa contraria se conserva como información");
        assertFalse(r.ranking().get(0).elegible());
        assertTrue(r.motivo().contains("sentido"));
        assertNull(Recomendador.recomendar(P, List.of()).idRecomendada());
    }

    @Test void unaRutaCircularEsUnaDirectaCualquieraSiElSentidoEsPermitido() throws Exception {
        var vacio = JsonMapper.builder().build().readTree("{}");
        var circular = new ViajeResponse.Opcion(5, "C1", "Circular", "#176650", false, 200, 100, 4.0, 300, true, "SENTIDO_DEL_TRAZADO", "CIRCULAR", true,
                vacio, vacio, vacio, "", true, null, null, null, null, "MENOS_CAMINATA").conId("D:5:MENOS_CAMINATA");
        var cand = Recomendador.candidatos(List.of(circular), List.of(), List.of());
        assertEquals("DIRECTA", cand.get(0).tipo());
        assertEquals("D:5:MENOS_CAMINATA", Recomendador.recomendar(P, cand).idRecomendada());
        assertEquals(300 / 80.0 + 4000 / 267.0, cand.get(0).minutos(), 1e-6);
    }

    // ---- Viabilidad por caminata extrema
    @Test void unaOpcionQueObligaACaminarMasDelLimiteNoEsLaRecomendadaSiHayOtra() {
        var r = Recomendador.recomendar(P, List.of(directa("D:1", 2500, 100, 50), transbordo("T:2>3", 500, 200, 300, 45)));
        assertEquals("T:2>3", r.idRecomendada());
        assertFalse(item(r, "D:1").elegible());
        assertTrue(item(r, "D:1").motivoNoElegible().contains("1800"), item(r, "D:1").motivoNoElegible());
    }

    @Test void siNingunaEstaDentroDelLimiteSeRecomiendaLaMenosMalaYNuncaNada() {
        var r = Recomendador.recomendar(P, List.of(directa("D:1", 2500, 100, 50), directa("D:2", 3200, 100, 40)));
        assertEquals("D:1", r.idRecomendada());
        assertTrue(item(r, "D:2").elegible());
    }

    // ---- Etiquetas: solo con comparación real y sin empate
    @Test void lasEtiquetasSoloApareceCuandoSeComparoDeVerdadYNoHayEmpate() {
        var sola = Recomendador.recomendar(P, List.of(directa("D:1", 300, 100, 25)));
        assertTrue(sola.ranking().get(0).etiquetas().isEmpty(), "una sola opción: no hay nada que comparar");
        var empate = Recomendador.recomendar(P, List.of(directa("D:1", 300, 100, 25), directa("D:2", 300, 100, 25)));
        assertTrue(empate.ranking().stream().allMatch(i -> i.etiquetas().isEmpty()), "empate exacto: nadie es «el mejor»");
        var dos = Recomendador.recomendar(P, List.of(directa("D:1", 300, 100, 25), directa("D:2", 500, 100, 20)));
        assertEquals(List.of("PRIMER_BUS_MAS_CERCANO", "MENOR_CAMINATA_TOTAL"), item(dos, "D:1").etiquetas());
        assertEquals(List.of("MENOR_TIEMPO"), item(dos, "D:2").etiquetas());
        assertFalse(dos.ranking().stream().anyMatch(i -> i.etiquetas().contains("SIN_TRANSBORDO")), "sin transbordos en la comparación no hay «sin transbordo»");
    }

    // ---- Dominadas
    @Test void unTransbordoClaramenteDominadoPorUnaDirectaSeDetecta() {
        var d = directa("D:1", 100, 100, 20);
        var t = transbordo("T:2>3", 150, 100, 100, 35);
        assertTrue(Recomendador.transbordoDominado(P, t, List.of(d, t)));
        var mejorAcceso = transbordo("T:2>3", 50, 100, 100, 35);
        assertFalse(Recomendador.transbordoDominado(P, mejorAcceso, List.of(d, mejorAcceso)), "mejora el acceso: no está dominado");
        assertFalse(Recomendador.transbordoDominado(P, t, List.of(contraria("A:1", 0, 0, 1), t)), "una contraria no domina");
    }

    // ---- Parámetros de la política
    @Test void laReduccionSignificativaExigeElMayorEntreElUmbralAbsolutoYElRelativo() {
        assertTrue(P.reduccionSignificativa(900, 100));          // 800 ≥ max(300, 450)
        assertFalse(P.reduccionSignificativa(900, 500));         // 400 < 450
        assertFalse(P.reduccionSignificativa(500, 300));         // 200 < 300
        assertTrue(P.reduccionSignificativa(500, 200));          // 300 ≥ max(300, 250)
        assertFalse(P.reduccionSignificativa(Double.NaN, 100));
        assertEquals(2, PoliticaRecomendacion.VERSION);
    }

    // ---- DTO: ids y compatibilidad
    @Test void losConstructoresAntiguosDelDtoSiguenFuncionandoYElIdSeRellenaConConId() throws Exception {
        var vacio = JsonMapper.builder().build().readTree("{}");
        var o = new ViajeResponse.Opcion(7, "R7", "Demo", "#176650", false, 120, 700, 3.0, 820, true, "SENTIDO_DEL_TRAZADO", "IDA", true,
                vacio, vacio, vacio, "", true, null, null, null, null, "MENOS_CAMINATA");
        assertNull(o.id());
        assertEquals("D:7:MENOS_CAMINATA", o.conId("D:7:MENOS_CAMINATA").id());
        var t = new ViajeResponse.Transbordo(20, 500, 5, 2, 8, null, null, null, null, null, "");
        assertNull(t.id());
        assertEquals("T:1>2", t.conId("T:1>2").id());
        assertNull(new ViajeResponse(List.of(), List.of(), "x").recomendacion());
    }
}
