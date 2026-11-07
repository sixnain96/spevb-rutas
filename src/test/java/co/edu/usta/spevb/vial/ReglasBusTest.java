package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.ReglasBus.Sentido;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static co.edu.usta.spevb.vial.Sinteticas.tags;
import static org.junit.jupiter.api.Assertions.*;

class ReglasBusTest {
    private static ReglasBus.Acceso ev(String... kv) { return ReglasBus.evaluar(tags(kv)); }

    @Test void aceptaLasViasVehicularesPermitidas() {
        for (String hw : List.of("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
                "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service",
                "busway", "bus_guideway", "road"))
            assertTrue(ev("highway", hw, "oneway", "no").transitable(), hw);
    }

    @Test void rechazaSiempreLasViasNoVehiculares() {
        for (String hw : List.of("footway", "path", "pedestrian", "steps", "cycleway", "corridor", "bridleway", "platform",
                "elevator", "proposed", "construction", "raceway", "bus_stop", "inventada"))
            assertFalse(ev("highway", hw).transitable(), hw);
        assertFalse(ReglasBus.evaluar(Map.of()).transitable());
        assertFalse(ReglasBus.evaluar(null).transitable());
        assertFalse(ev("name", "sin highway").transitable());
    }

    @Test void unPuentePeatonalSeRechazaPorSuHighway() {
        assertFalse(ev("highway", "footway", "bridge", "yes", "layer", "1").transitable());
        assertTrue(ev("highway", "primary", "bridge", "yes", "layer", "1").transitable(), "un puente vehicular sí se acepta");
    }

    @Test void trackSoloConBusYesODesignated() {
        assertFalse(ev("highway", "track").transitable());
        assertFalse(ev("highway", "track", "access", "yes").transitable());
        assertTrue(ev("highway", "track", "bus", "yes").transitable());
        assertTrue(ev("highway", "track", "bus", "designated").transitable());
    }

    @Test void accesoNoOPrivadoRechazaPeroBusDesignatedLoPermite() {
        assertFalse(ev("highway", "residential", "access", "no").transitable());
        assertFalse(ev("highway", "residential", "access", "private").transitable());
        assertFalse(ev("highway", "service", "access", "private").transitable(), "service privado se rechaza");
        assertTrue(ev("highway", "residential", "access", "no", "bus", "designated").transitable());
        assertTrue(ev("highway", "residential", "access", "private", "psv", "yes").transitable());
        assertTrue(ev("highway", "service", "access", "no", "bus", "official").transitable());
        assertTrue(ev("highway", "service", "access", "no", "bus", "permit").transitable());
    }

    @Test void motorVehicleNoRechazaSalvoQueBusLoPermita() {
        assertFalse(ev("highway", "residential", "motor_vehicle", "no").transitable());
        assertFalse(ev("highway", "residential", "motor_vehicle", "private").transitable());
        assertTrue(ev("highway", "residential", "motor_vehicle", "no", "bus", "yes").transitable());
        assertTrue(ev("highway", "residential", "motor_vehicle", "no", "psv", "designated").transitable());
        assertFalse(ev("highway", "residential", "vehicle", "no").transitable());
        assertTrue(ev("highway", "residential", "vehicle", "no", "bus", "yes").transitable());
    }

    @Test void busNoOPsvNoRechazanAunqueElAccesoSeaAbierto() {
        assertFalse(ev("highway", "residential", "bus", "no").transitable());
        assertFalse(ev("highway", "residential", "psv", "no").transitable());
        assertFalse(ev("highway", "residential", "access", "yes", "bus", "no").transitable());
    }

    @Test void precedenciaDeAccesoBusPsvMotorVehicleVehicleAccess() {
        assertTrue(ev("highway", "residential", "access", "no", "vehicle", "no", "motor_vehicle", "yes").transitable(), "motor_vehicle > vehicle > access");
        assertTrue(ev("highway", "residential", "access", "no", "motor_vehicle", "no", "psv", "yes").transitable(), "psv > motor_vehicle");
        assertFalse(ev("highway", "residential", "bus", "no", "psv", "yes").transitable(), "bus > psv");
        assertTrue(ev("highway", "residential", "bus", "yes", "psv", "no").transitable(), "bus > psv");
        assertFalse(ev("highway", "residential", "vehicle", "no", "access", "yes").transitable(), "vehicle > access");
    }

    @Test void construccionEnHighwayOEnTagConstruction() {
        assertFalse(ev("highway", "construction", "construction", "residential").transitable());
        assertFalse(ev("highway", "proposed").transitable());
        assertFalse(ev("highway", "residential", "construction", "yes").transitable());
        assertTrue(ev("highway", "residential", "construction", "no").transitable());
    }

    @Test void sentidosPorOneway() {
        assertEquals(Sentido.AMBOS, ev("highway", "residential").sentido());
        for (String v : List.of("yes", "true", "1")) assertEquals(Sentido.SOLO_ADELANTE, ev("highway", "residential", "oneway", v).sentido(), v);
        for (String v : List.of("-1", "reverse")) assertEquals(Sentido.SOLO_ATRAS, ev("highway", "residential", "oneway", v).sentido(), v);
        assertEquals(Sentido.AMBOS, ev("highway", "residential", "oneway", "no").sentido());
    }

    @Test void glorietaImplicaSentidoUnicoAdelanteSalvoOnewayExplicito() {
        assertEquals(Sentido.SOLO_ADELANTE, ev("highway", "primary", "junction", "roundabout").sentido());
        assertEquals(Sentido.SOLO_ADELANTE, ev("highway", "primary", "junction", "circular").sentido());
        assertEquals(Sentido.AMBOS, ev("highway", "primary", "junction", "roundabout", "oneway", "no").sentido());
        assertEquals(Sentido.SOLO_ATRAS, ev("highway", "primary", "junction", "roundabout", "oneway", "-1").sentido());
    }

    @Test void onewayBusYPsvTienenPrecedenciaSobreOneway() {
        assertEquals(Sentido.AMBOS, ev("highway", "residential", "oneway", "yes", "oneway:bus", "no").sentido(), "carril bus contraflujo");
        assertEquals(Sentido.AMBOS, ev("highway", "residential", "oneway", "yes", "oneway:psv", "no").sentido());
        assertEquals(Sentido.SOLO_ATRAS, ev("highway", "residential", "oneway", "yes", "oneway:bus", "-1").sentido());
        assertEquals(Sentido.SOLO_ADELANTE, ev("highway", "residential", "oneway", "no", "oneway:bus", "yes").sentido());
        assertEquals(Sentido.AMBOS, ev("highway", "residential", "oneway", "yes", "oneway:psv", "yes", "oneway:bus", "no").sentido(), "oneway:bus > oneway:psv");
    }

    @Test void elMotivoSiempreExplicaLaDecision() {
        assertFalse(ev("highway", "steps").motivo().isBlank());
        assertFalse(ev("highway", "residential", "access", "no").motivo().isBlank());
        assertFalse(ev("highway", "residential").motivo().isBlank());
    }
}
