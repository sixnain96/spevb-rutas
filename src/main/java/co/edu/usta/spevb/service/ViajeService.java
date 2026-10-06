package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class ViajeService {
    private final ViajeRepository repository;
    private final ObjectMapper mapper;
    private final CityArea cityArea;

    public ViajeService(ViajeRepository repository, ObjectMapper mapper, CityArea cityArea) {
        this.repository = repository;
        this.mapper = mapper;
        this.cityArea = cityArea;
    }

    public ViajeResponse planificar(PlanificarViajeRequest request) {
        if (!Double.isFinite(request.origen().lat()) || !Double.isFinite(request.origen().lng())
                || !Double.isFinite(request.destino().lat()) || !Double.isFinite(request.destino().lng()))
            throw new IllegalArgumentException("Las coordenadas deben ser números finitos.");
        cityArea.requireInside(request.origen().lat(), request.origen().lng());
        cityArea.requireInside(request.destino().lat(), request.destino().lng());

        List<ViajeResponse.Opcion> todas = repository.planificar(request, 60).stream()
                .map(this::toOption).toList();
        // Sin límite de distancia: la primera opción es siempre la ruta registrada más cercana.
        List<ViajeResponse.Opcion> directas = todas.stream().filter(ViajeResponse.Opcion::sentidoPermitido).limit(12).toList();
        List<ViajeResponse.Opcion> alternativas = todas.stream().filter(o -> !o.sentidoPermitido()).limit(5).toList();

        String alcance;
        if (directas.isEmpty() && alternativas.isEmpty()) {
            alcance = "Todavía no hay rutas registradas que conecten estos dos puntos. Cuando registres más rutas, el cálculo las usará automáticamente.";
        } else if (directas.isEmpty()) {
            alcance = "Las rutas registradas sólo conectan estos puntos recorriéndolas en sentido contrario al dibujado. Registra la ruta de regreso para obtener una opción directa.";
        } else {
            alcance = "Se muestran las rutas registradas más cercanas, sin importar la distancia, ordenadas por lo que hay que caminar desde A hasta la ruta y desde la ruta hasta B. Las líneas punteadas son distancias en línea recta, no un camino peatonal.";
        }
        return new ViajeResponse(directas, alternativas, alcance);
    }

    private ViajeResponse.Opcion toOption(ViajeRepository.OpcionRow r) {
        String advertencia = r.sentidoPermitido()
                ? "Los puntos de subida y bajada se proyectan sobre la línea registrada. Confirma en campo el paradero permitido."
                : "Esta ruta está registrada en sentido " + r.sentidoRuta() + " y el tramo propuesto va al revés. "
                  + "Sólo sirve si el bus también circula de regreso por estas calles; si es así, registra la ruta "
                  + "de regreso o, si se dibujó al revés, usa «Invertir sentido» en el editor. Confirma en campo.";
        return new ViajeResponse.Opcion(
                r.id(), r.codigo(), r.nombre(), r.color(), r.validada(),
                r.inicioMetros(), r.finMetros(), r.tramoKm(), r.accesoTotalMetros(),
                r.dentroLimite(), r.sentidoViaje(), r.sentidoRuta(), r.sentidoPermitido(),
                mapper.readTree(r.subida()), mapper.readTree(r.bajada()), mapper.readTree(r.tramo()),
                advertencia
        );
    }
}
