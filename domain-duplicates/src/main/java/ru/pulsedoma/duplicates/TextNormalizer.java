package ru.pulsedoma.duplicates;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public final class TextNormalizer {
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("электричество", "свет"), Map.entry("электроэнергия", "свет"),
            Map.entry("лампа", "свет"), Map.entry("лампочка", "свет"),
            Map.entry("освещение", "свет"), Map.entry("подъезде", "подъезд"),
            Map.entry("подъезда", "подъезд"), Map.entry("лифта", "лифт"),
            Map.entry("лифты", "лифт"), Map.entry("течет", "течь"),
            Map.entry("протечка", "течь"),
            Map.entry("водоснабжение", "вода"), Map.entry("отопление", "тепло"),
            Map.entry("батареи", "тепло"), Map.entry("батарея", "тепло")
    );

    public String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (cleaned.isEmpty()) return "";
        return Arrays.stream(cleaned.split("\\s+"))
                .map(word -> SYNONYMS.getOrDefault(word, word))
                .collect(Collectors.joining(" "));
    }
}
