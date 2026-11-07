package co.edu.usta.spevb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DireccionParserTest {
    private static DireccionParser.Direccion p(String s) { return DireccionParser.parsear(s); }

    @Test void userFormatWithHashAndCardinal() {
        var d = p("Carrera 48 #19-15 Sur");
        assertEquals("K", d.tipoVia()); assertEquals("48", d.via()); assertEquals("19", d.cruce());
        assertEquals(15, d.placa()); assertEquals("S", d.cuadrante()); assertTrue(d.estructurada());
    }

    @Test void abbreviationsAndNumberWords() {
        for (String s : new String[]{"Cra 48 No. 19 - 15", "kr 48 # 19-15", "CRA 48 NRO 19 15", "Carrera 48 número 19 15", "cr48 19 15"}) {
            var d = p(s);
            assertEquals("K|48||19|15", d.clave(), s);
        }
    }

    @Test void catastroFormatWithSouthAttachedToTheStreet() {
        var d = p("C 17SUR 39 03 MZ B CS 10 URB SAN J");
        assertEquals("C|17|S|39|3", d.clave());
        assertEquals("B", d.manzana()); assertEquals("10", d.casa()); assertEquals("SAN J", d.barrio());
    }

    @Test void userWritingSouthAfterTheStreetNumber() {
        assertEquals("C|17|S|39|3", p("Calle 17 Sur # 39-03").clave());
        assertEquals("C|17|S|39|3", p("cl 17 sur 39 03").clave());
    }

    @Test void lettersBisAndAttachedType() {
        assertEquals("K|34||9C|24", p("K34 9C 24 CS 7 ET 6 BR LA ESPERANZ").clave());
        assertEquals("C|37BIS||38|23", p("C 37BIS 38 23 BR EL BARZAL").clave());
        assertEquals("C|37BISA||21B|27", p("C 37BIS A 21B 27 29 BR SANTA FE").clave());
        assertEquals(29, p("C 37BIS A 21B 27 29 BR SANTA FE").placa2());
    }

    @Test void leadingZerosInPlateAreIgnored() {
        assertEquals("K|19||4C|3", p("K 19 4C 03 MZ C CS 13 URB VIZCAYA").clave());
    }

    @Test void intersectionOfTwoStreets() {
        var d = p("Calle 40 con Carrera 25");
        assertTrue(d.esInterseccion());
        assertEquals("C", d.tipoVia()); assertEquals("40", d.via());
        assertEquals("K", d.interseccionTipo()); assertEquals("25", d.interseccionVia());
        assertFalse(d.estructurada());
    }

    @Test void neighbourhoodBlockAndHouse() {
        var d = p("barrio San Marcos manzana C casa 5");
        assertTrue(d.esManzanaCasa());
        assertEquals("SAN MARCOS", d.barrio()); assertEquals("C", d.manzana()); assertEquals("5", d.casa());
        var e = p("san marcos mz c cs 5");
        assertTrue(e.esManzanaCasa()); assertEquals("SAN MARCOS", e.barrio());
    }

    @Test void partialAddressKeepsTheTypedCross() {
        var d = p("Carrera 48 #19");
        assertEquals("48", d.via()); assertEquals("19", d.cruceParcial()); assertFalse(d.estructurada());
    }

    @Test void namedAvenueAndGarbage() {
        assertEquals("CATAMA", p("Avenida Catama").viaNombre());
        assertFalse(p("0").estructurada());
        assertFalse(p("").estructurada());
        assertFalse(p("Unicentro Villavicencio").estructurada());
    }

    @Test void blockLetterSIsNotTheSouthQuadrant() {
        var d = p("C 12 15 20 MZ S CS 4 BR CENTRO");
        assertEquals("", d.cuadrante());
        assertEquals("S", d.manzana());
    }
}
