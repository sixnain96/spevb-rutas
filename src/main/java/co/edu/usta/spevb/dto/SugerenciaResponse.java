package co.edu.usta.spevb.dto;

/** Sugerencia de autocompletado. Las locales traen coordenadas (se ubican sin otra consulta); las de ArcGIS traen magicKey. */
public record SugerenciaResponse(String texto, String detalle, String magicKey, Double lat, Double lng,
                                 Long id, String fuente, String precision) {}
