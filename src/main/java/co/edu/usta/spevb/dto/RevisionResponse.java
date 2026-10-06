package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;

public record RevisionResponse(long version, String archivadoEn, JsonNode datos) {}
