package com.mazerunner.maze;

import java.util.List;
import java.util.Map;

/**
 * Bitmaps sencillos (texto '#'/'.') usados para grabar en las paredes tanto el número de
 * cada sector como algunos símbolos decorativos sueltos, con bloques que contrastan con el
 * material de la pared de alrededor.
 */
public final class PixelFonts {

    private PixelFonts() {}

    /** Dígitos 5 de ancho x 7 de alto (solo hacen falta 0-7, hay como mucho 7 sectores). */
    public static final Map<Integer, String[]> DIGITS = Map.of(
            0, new String[]{"01110", "10001", "10011", "10101", "11001", "10001", "01110"},
            1, new String[]{"00100", "01100", "00100", "00100", "00100", "00100", "01110"},
            2, new String[]{"01110", "10001", "00001", "00010", "00100", "01000", "11111"},
            3, new String[]{"11111", "00010", "00100", "00010", "00001", "10001", "01110"},
            4, new String[]{"00010", "00110", "01010", "10010", "11111", "00010", "00010"},
            5, new String[]{"11111", "10000", "11110", "00001", "00001", "10001", "01110"},
            6, new String[]{"00110", "01000", "10000", "11110", "10001", "10001", "01110"},
            7, new String[]{"11111", "00001", "00010", "00100", "01000", "01000", "01000"}
    );

    /** Símbolos 5x5 sueltos para decorar algunos tramos de pared del laberinto. */
    public static final List<String[]> SYMBOLS = List.of(
            new String[]{"00100", "00100", "11111", "00100", "00100"}, // cruz
            new String[]{"00100", "01110", "11111", "01110", "00100"}, // rombo
            new String[]{"10001", "01010", "00100", "01010", "10001"}, // aspa
            new String[]{"00100", "00100", "01010", "01010", "11111"}  // flecha/triángulo
    );
}
