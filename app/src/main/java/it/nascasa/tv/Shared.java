package it.nascasa.tv;

import java.util.ArrayList;
import java.util.List;

/**
 * Elenchi passati da una schermata all'altra (le liste possono avere migliaia di voci:
 * troppo grandi per viaggiare dentro un Intent).
 */
final class Shared {
    private Shared() {}

    static List<String> photoUrls = new ArrayList<>();
    static List<String> photoTitles = new ArrayList<>();
    static List<String> photoMimes = new ArrayList<>();

    static List<String> mediaUrls = new ArrayList<>();
    static List<String> mediaTitles = new ArrayList<>();
}
