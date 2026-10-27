package co.edu.usta.spevb.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Mide el intérprete contra las direcciones reales del catastro si se indica SEVB_CORPUS_DIR (se omite si no). */
class DireccionParserCorpusTest {
    @Test void corpus() throws Exception {
        String dir = System.getenv("SEVB_CORPUS_DIR");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)));
        var mapper = JsonMapper.builder().build();
        int total = 0, estructuradas = 0, conBarrio = 0, junk = 0;
        Map<String, Integer> fallos = new TreeMap<>();
        List<String> ejemplos = new ArrayList<>();
        try (var files = Files.list(Path.of(dir))) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("p\\d+\\.json")).toList()) {
                for (var feature : mapper.readTree(Files.readAllBytes(f)).path("features")) {
                    String texto = feature.path("attributes").path("DIRECCION").asText("");
                    total++;
                    var d = DireccionParser.parsear(texto);
                    if (d.estructurada()) estructuradas++;
                    else if (texto.strip().length() <= 3) junk++;
                    else { String k = d.tipoVia() + "/" + (d.via().isEmpty() ? "-" : "via") + "/" + (d.cruce().isEmpty() ? "-" : "cruce") + "/" + (d.placa() > 0 ? "placa" : "-");
                        fallos.merge(k, 1, Integer::sum); if (ejemplos.size() < 60 && Math.random() < 0.02) ejemplos.add(texto + "  =>  " + k); }
                    if (!d.barrio().isEmpty()) conBarrio++;
                }
            }
        }
        System.out.printf("CORPUS total=%d estructuradas=%d (%.1f%%) junk=%d conBarrio=%d%n", total, estructuradas, 100.0 * estructuradas / total, junk, conBarrio);
        System.out.println("CORPUS fallos=" + fallos);
        ejemplos.forEach(e -> System.out.println("CORPUS ej: " + e));
    }
}
