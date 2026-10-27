package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.ActualizarRecorridoRequest;
import co.edu.usta.spevb.exception.*;
import co.edu.usta.spevb.repository.RutaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RutaServiceTest {
    private RutaRepository repository;
    private RutaService service;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String line = "{\"type\":\"LineString\",\"coordinates\":[[-73.62,4.14],[-73.63,4.15]]}";

    @BeforeEach void setup() {
        repository = mock(RutaRepository.class);
        service = new RutaService(repository, mapper, new GeometriaValidator(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45));
    }

    private RutaRepository.RutaDetalleRow row() {
        return new RutaRepository.RutaDetalleRow(1L,"R001","Ruta","Inicio","Fin","IDA","", "#1565C0",
                BigDecimal.ONE,true,false,true,"GPS", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.14]}",
                "{\"type\":\"Point\",\"coordinates\":[-73.63,4.15]}",line,3,"2026-10-01T00:00:00Z","Revisada");
    }

    private ActualizarRecorridoRequest request(long version, boolean validada, String source, String note) {
        return new ActualizarRecorridoRequest(mapper.readTree(line),"Ruta"," Inicio "," Fin ","IDA","Manual","#176650",source,validada,version,note);
    }

    @Test void rejectsStaleVersionWithoutWriting() {
        when(repository.findById(1L)).thenReturn(Optional.of(row()));
        assertThrows(ConflictoVersionException.class, () -> service.actualizarRecorrido(1L,request(2,false,"GPS",null)));
        verify(repository, never()).archivar(any(),anyLong());
    }

    @Test void requiresEvidenceForValidation() {
        when(repository.findById(1L)).thenReturn(Optional.of(row()));
        assertThrows(IllegalArgumentException.class, () -> service.actualizarRecorrido(1L,request(3,true,"GPS","")));
        assertThrows(IllegalArgumentException.class, () -> service.actualizarRecorrido(1L,request(3,true,"","Revisada")));
        verify(repository, never()).archivar(any(),anyLong());
    }

    @Test void archivesThenUpdatesAndClearsOldEvidenceWhenUnvalidated() {
        when(repository.findById(1L)).thenReturn(Optional.of(row()));
        when(repository.actualizarRecorrido(eq(1L),anyString(),eq("Ruta"),eq("Inicio"),eq("Fin"),eq("IDA"),eq("Manual"),eq("#176650"),eq("GPS"),eq(false),eq(3L),isNull())).thenReturn(true);
        when(repository.findParaderos(1L)).thenReturn(List.of());
        service.actualizarRecorrido(1L,request(3,false," GPS ","Antigua"));
        var order = inOrder(repository);
        order.verify(repository).archivar(1L,3L);
        order.verify(repository).actualizarRecorrido(eq(1L),anyString(),eq("Ruta"),eq("Inicio"),eq("Fin"),eq("IDA"),eq("Manual"),eq("#176650"),eq("GPS"),eq(false),eq(3L),isNull());
    }

    @Test void detectsConcurrentUpdate() {
        when(repository.findById(1L)).thenReturn(Optional.of(row()));
        assertThrows(ConflictoVersionException.class, () -> service.actualizarRecorrido(1L,request(3,false,"GPS",null)));
    }

    @Test void nonexistentRouteIs404DomainError() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RutaNoEncontradaException.class, () -> service.obtenerRuta(99L));
    }

    @Test void exposesStopDistanceAndWarnsWhenFarFromRoute() {
        when(repository.findById(1L)).thenReturn(Optional.of(row()));
        when(repository.findParaderos(1L)).thenReturn(List.of(new RutaRepository.ParaderoRow(5L,1,"Parada",
                "{\"type\":\"Point\",\"coordinates\":[-73.6,4.1]}",250)));
        var detail = service.obtenerRuta(1L);
        assertEquals(3,detail.version()); assertEquals(250,detail.paraderos().getFirst().distanciaRecorridoMetros());
        assertEquals(1,detail.advertencias().size());
    }
}
