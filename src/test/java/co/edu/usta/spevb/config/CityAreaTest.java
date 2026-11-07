package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CityAreaTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);

    private void normaliza(String entrada, String esperado) {
        assertEquals(esperado, area.normalizeAddress(entrada), "Entrada: " + entrada);
    }

    @Test void expandeAbreviaturasDeVia() {
        normaliza("Cra 33 # 15-20", "Carrera 33 # 15-20");
        normaliza("cra. 33 #15-20", "Carrera 33 # 15-20");
        normaliza("Kra 40 # 26-10", "Carrera 40 # 26-10");
        normaliza("KR 40 # 26-10", "Carrera 40 # 26-10");
        normaliza("Cr 22 # 8-15", "Carrera 22 # 8-15");
        normaliza("Cll 7 # 30-10", "Calle 7 # 30-10");
        normaliza("Cl. 7 # 30-10", "Calle 7 # 30-10");
        normaliza("Av 40 # 26-10", "Avenida 40 # 26-10");
        normaliza("Av. Catama", "Avenida Catama");
        normaliza("Diag 15 # 3-20", "Diagonal 15 # 3-20");
        normaliza("Dg 15 # 3-20", "Diagonal 15 # 3-20");
        normaliza("Tv 27 # 5-50", "Transversal 27 # 5-50");
        normaliza("Transv 27 # 5-50", "Transversal 27 # 5-50");
    }

    @Test void noModificaNombresCompletosDeVia() {
        normaliza("Avenida 40", "Avenida 40");
        normaliza("Avenida Catama", "Avenida Catama");
        normaliza("Avenida Las Américas", "Avenida Las Américas");
        normaliza("Diagonal 15", "Diagonal 15");
        normaliza("Transversal 27", "Transversal 27");
        normaliza("Carrera 33", "Carrera 33");
        normaliza("Calle 7", "Calle 7");
    }

    @Test void noModificaPalabrasQueEmpiezanComoUnaAbreviatura() {
        normaliza("Clínica Meta", "Clínica Meta");
        normaliza("Cristo Rey", "Cristo Rey");
        normaliza("Barrio Nogal", "Barrio Nogal");
        normaliza("Barrio El Nogal Norte", "Barrio El Nogal Norte");
        normaliza("Avianca", "Avianca");
        normaliza("Hospital Departamental", "Hospital Departamental");
        normaliza("Centro Comercial Unicentro", "Centro Comercial Unicentro");
        normaliza("Trinidad", "Trinidad");
        normaliza("Barrio Kirpas", "Barrio Kirpas");
    }

    @Test void interpretaNoComoNumeroSoloAntesDeUnDigito() {
        normaliza("Calle 7 No. 30-10", "Calle 7 # 30-10");
        normaliza("Calle 7 No 30-10", "Calle 7 # 30-10");
        normaliza("Calle 7 Nº 30-10", "Calle 7 # 30-10");
        normaliza("Calle 7 N° 30-10", "Calle 7 # 30-10");
        normaliza("No hay nombre", "No hay nombre");
    }

    @Test void conviertePlacasEscritasConA() {
        normaliza("Carrera 27 Norte 5 a 50", "Carrera 27 Norte # 5-50");
        normaliza("Carrera 27 Norte # 5 a 50", "Carrera 27 Norte # 5-50");
        normaliza("Cra 27 5 a 50", "Carrera 27 # 5-50");
        normaliza("Calle 15 Sur 8 - 20", "Calle 15 Sur # 8-20");
        normaliza("Calle 15 # 5A-20", "Calle 15 # 5A-20");
        normaliza("Calle 15 # 5A a 20", "Calle 15 # 5A-20");
    }

    @Test void agregaLaCiudadSoloCuandoFalta() {
        assertEquals("Avenida 40, Villavicencio, Meta, Colombia", area.searchQuery("Av 40"));
        assertEquals("Avenida 40 Villavicencio", area.searchQuery("Av 40 Villavicencio"));
    }

    @Test void laVarianteRelajadaQuitaNumeralYGuion() {
        var consultas = area.searchQueries("Cra 33 # 15-20");
        assertEquals(2, consultas.size());
        assertEquals("Carrera 33 # 15-20, Villavicencio, Meta, Colombia", consultas.get(0));
        assertEquals("Carrera 33 15 20, Villavicencio, Meta, Colombia", consultas.get(1));
    }
}
