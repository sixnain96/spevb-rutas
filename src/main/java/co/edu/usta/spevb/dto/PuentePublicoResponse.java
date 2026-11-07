package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;

/** Proyección pública de un puente peatonal: solo lo que necesita el mapa. No incluye la nota ni datos administrativos. */
public record PuentePublicoResponse(long id, String nombre, long metros, JsonNode trazo) {}
