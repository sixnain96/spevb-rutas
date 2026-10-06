package co.edu.usta.spevb.dto;

public record LugarResponse(
        Long id,
        String nombre,
        String direccion,
        double lat,
        double lng,
        String fuente,
        boolean confirmado
) {}
