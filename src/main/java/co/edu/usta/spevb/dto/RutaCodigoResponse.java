package co.edu.usta.spevb.dto;

/** Resolución de un código de ruta (vigente o anterior): {@code aliasUsado} es true si el código consultado ya no es el vigente. */
public record RutaCodigoResponse(long id, String codigo, String nombre, boolean aliasUsado, String codigoConsultado) {}
