package ru.pulsedoma.duplicates;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Category-specific hints for free-form places, never an automatic merge decision. */
public final class LocationMatcher {
    private enum Policy { ELEVATOR, INDOOR, OUTDOOR, STRICT }
    private enum Lift { PASSENGER, CARGO }
    private enum Zone { FRONT, BACK, LEFT, RIGHT }
    private enum ObjectType { SWING, SLIDE, SANDBOX, BENCH, BIN, PLAYGROUND, PATH, PARKING }
    public record Match(boolean compatible, double score, List<String> reasons) {}
    private static final Match DIFFERENT = new Match(false, 0, List.of());
    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("подьезд", "подъезд"), Map.entry("подьезде", "подъезд"), Map.entry("подьезда", "подъезд"),
            Map.entry("подъезде", "подъезд"), Map.entry("подъезда", "подъезд"), Map.entry("подъезду", "подъезд"),
            Map.entry("этаже", "этаж"), Map.entry("этажа", "этаж"), Map.entry("этажу", "этаж"),
            Map.entry("квартире", "квартира"), Map.entry("квартиры", "квартира"),
            Map.entry("лифте", "лифт"), Map.entry("лифта", "лифт"),
            Map.entry("дворе", "двор"), Map.entry("двора", "двор"),
            Map.entry("площадке", "площадка"), Map.entry("площадки", "площадка"),
            Map.entry("контейнера", "контейнер"), Map.entry("контейнере", "контейнер"),
            Map.entry("горке", "горка"), Map.entry("горки", "горка"),
            Map.entry("качелях", "качели"), Map.entry("качелей", "качели"),
            Map.entry("песочнице", "песочница"), Map.entry("песочницы", "песочница")
    );
    private static final String[][] ORDINALS = {
            {"первый", "первом", "первого", "первым", "первая", "первой"},
            {"второй", "втором", "второго", "вторым", "вторая"},
            {"третий", "третьем", "третьего", "третьим", "третья", "третьей"},
            {"четвертый", "четвертом", "четвертого", "четвертым", "четвертая", "четвертой"},
            {"пятый", "пятом", "пятого", "пятым", "пятая", "пятой"},
            {"шестой", "шестом", "шестого", "шестым", "шестая"},
            {"седьмой", "седьмом", "седьмого", "седьмым", "седьмая"},
            {"восьмой", "восьмом", "восьмого", "восьмым", "восьмая"},
            {"девятый", "девятом", "девятого", "девятым", "девятая", "девятой"},
            {"десятый", "десятом", "десятого", "десятым", "десятая", "десятой"}
    };
    private static final Set<String> FILLER = Set.of("в", "во", "на", "у", "около", "возле", "й", "ый", "ой", "я", "номер");
    private static final Set<String> AXES = Set.of("подъезд", "этаж", "квартира", "лифт", "площадка", "контейнер", "качели", "горка", "песочница");

    public Match compare(String category, String input, String candidate) {
        String a = normalize(input), b = normalize(candidate);
        if (a.isBlank() || b.isBlank()) {
            return a.isBlank() ? new Match(true, 0, List.of("location_unspecified")) : DIFFERENT;
        }
        Integer entranceA = number(a, "подъезд"), entranceB = number(b, "подъезд");
        if (conflicts(entranceA, entranceB)) return DIFFERENT;
        Policy policy = switch (category == null ? "" : category) {
            case "ELEVATOR" -> Policy.ELEVATOR;
            case "LIGHTING", "WATER", "HEATING", "ENTRANCE_CLEANING" -> Policy.INDOOR;
            case "YARD_CLEANING", "WASTE_REMOVAL", "PLAYGROUND" -> Policy.OUTDOOR;
            default -> Policy.STRICT;
        };
        if (policy == Policy.ELEVATOR) {
            if (conflicts(lift(a), lift(b)) || conflicts(number(a, "лифт"), number(b, "лифт"))) return DIFFERENT;
            if (entranceA != null && entranceA.equals(entranceB)) {
                return new Match(true, lift(a) != null && lift(a) == lift(b) ? 1 : .85,
                        List.of("same_entrance", "elevator_floor_ignored"));
            }
        }
        if (policy == Policy.INDOOR) {
            Integer floorA = number(a, "этаж"), floorB = number(b, "этаж");
            Integer apartmentA = number(a, "квартира"), apartmentB = number(b, "квартира");
            if (conflicts(floorA, floorB) || conflicts(apartmentA, apartmentB)) return DIFFERENT;
            if (entranceA != null && entranceA.equals(entranceB)) {
                if (floorA != null && floorA.equals(floorB)) {
                    return new Match(true, (apartmentA == null) == (apartmentB == null) ? 1 : .75,
                            List.of("same_entrance", "same_floor"));
                }
                // An unspecified floor can be a hint only when no other place detail conflicts.
                if ((floorA == null) != (floorB == null) && canonical(withoutFloor(a)).equals(canonical(withoutFloor(b)))) {
                    return new Match(true, .65, List.of("same_entrance", "floor_unspecified"));
                }
            }
        }
        if (policy == Policy.OUTDOOR) {
            if (conflicts(zone(a), zone(b)) || conflicts(number(a, "площадка|контейнер|качели|горка|песочница"),
                    number(b, "площадка|контейнер|качели|горка|песочница"))) return DIFFERENT;
            Set<ObjectType> objectsA = objects(a), objectsB = objects(b);
            if (!objectsA.isEmpty() && !objectsB.isEmpty()) {
                Set<ObjectType> shared = new HashSet<>(objectsA);
                shared.retainAll(objectsB);
                if (shared.isEmpty()) return DIFFERENT;
                return new Match(true, canonical(a).equals(canonical(b)) ? 1 : .8,
                        List.of("same_outdoor_object", "check_outdoor_zone"));
            }
        }
        return canonical(a).equals(canonical(b))
                ? new Match(true, 1, List.of("same_location")) : DIFFERENT;
    }

    public LocationFeatures legacyFeatures(String category, String label) {
        String text = normalize(label);
        var area = text.contains("весь дом") ? LocationFeatures.Area.WHOLE_HOUSE
                : text.contains("квартир") ? LocationFeatures.Area.APARTMENT
                : text.contains("подвал") ? LocationFeatures.Area.BASEMENT
                : text.contains("двор") || text.contains("территор") || !objects(text).isEmpty() ? LocationFeatures.Area.YARD
                : text.contains("подъезд") ? LocationFeatures.Area.ENTRANCE : LocationFeatures.Area.OTHER;
        var type = lift(text) == Lift.CARGO ? LocationFeatures.LiftType.CARGO
                : lift(text) == Lift.PASSENGER ? LocationFeatures.LiftType.PASSENGER : LocationFeatures.LiftType.UNKNOWN;
        var object = objects(text).contains(ObjectType.SWING) ? LocationFeatures.OutdoorObject.SWING
                : objects(text).contains(ObjectType.SLIDE) ? LocationFeatures.OutdoorObject.SLIDE
                : objects(text).contains(ObjectType.SANDBOX) ? LocationFeatures.OutdoorObject.SANDBOX : null;
        return new LocationFeatures(area, number(text, "подъезд"), number(text, "этаж"),
                category.equals("ELEVATOR") ? type : null, number(text, "лифт"),
                category.equals("ENTRANCE_CLEANING") ? text.contains("весь подъезд") ? LocationFeatures.Coverage.ENTIRE : LocationFeatures.Coverage.LOCAL : null,
                object, category.equals("WASTE_REMOVAL") ? number(text, "площадка|контейнер") : null, label);
    }

    private static String normalize(String text) {
        String cleaned = (text == null ? "" : text.toLowerCase(java.util.Locale.ROOT)).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("([\\p{L}])([0-9])", "$1 $2").trim();
        return Arrays.stream(cleaned.split("\\s+")).map(word -> {
            for (int i = 0; i < ORDINALS.length; i++) {
                for (String ordinal : ORDINALS[i]) if (word.equals(ordinal)) return String.valueOf(i + 1);
            }
            return WORDS.getOrDefault(word, word);
        }).collect(Collectors.joining(" "));
    }

    private static Integer number(String text, String nouns) {
        String[] tokens = text.split("\\s+");
        Set<String> wanted = Set.of(nouns.split("\\|"));
        Set<Integer> usedNumbers = new HashSet<>();
        Integer value = null;
        for (int i = 0; i < tokens.length; i++) {
            if (!AXES.contains(tokens[i])) continue;
            int previous = i - 1;
            if (previous >= 0 && Set.of("й", "ый", "ой", "я").contains(tokens[previous])) previous--;
            int next = i + 1;
            if (next < tokens.length && tokens[next].equals("номер")) next++;
            int index = previous >= 0 && tokens[previous].matches("[0-9]{1,8}") && !usedNumbers.contains(previous)
                    ? previous : next < tokens.length && tokens[next].matches("[0-9]{1,8}") && !usedNumbers.contains(next) ? next : -1;
            if (index == -1) continue;
            usedNumbers.add(index);
            if (!wanted.contains(tokens[i])) continue;
            int found = Integer.parseInt(tokens[index]);
            if (value != null && value != found) return null;
            value = found;
        }
        return value;
    }

    private static String withoutFloor(String text) {
        String[] tokens = text.split("\\s+");
        Set<Integer> used = new HashSet<>(), removed = new HashSet<>();
        for (int i = 0; i < tokens.length; i++) {
            if (!AXES.contains(tokens[i])) continue;
            int previous = i - 1;
            if (previous >= 0 && Set.of("й", "ый", "ой", "я").contains(tokens[previous])) previous--;
            int next = i + 1;
            if (next < tokens.length && tokens[next].equals("номер")) next++;
            int index = previous >= 0 && tokens[previous].matches("[0-9]{1,8}") && !used.contains(previous)
                    ? previous : next < tokens.length && tokens[next].matches("[0-9]{1,8}") && !used.contains(next) ? next : -1;
            if (index >= 0) used.add(index);
            if (tokens[i].equals("этаж")) {
                removed.add(i);
                if (index >= 0) removed.add(index);
            }
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) if (!removed.contains(i)) result.append(tokens[i]).append(' ');
        return result.toString();
    }

    private static String canonical(String text) {
        return Arrays.stream(text.trim().split("\\s+")).filter(word -> !word.isBlank() && !FILLER.contains(word))
                .sorted().collect(Collectors.joining(" "));
    }

    private static boolean conflicts(Object a, Object b) { return a != null && b != null && !a.equals(b); }
    private static Lift lift(String s) {
        if (s.contains("грузов")) return Lift.CARGO;
        return s.contains("пассажирск") ? Lift.PASSENGER : null;
    }
    private static Zone zone(String s) {
        if (s.contains("за дом") || s.contains("сзади")) return Zone.BACK;
        if (s.contains("перед дом") || s.contains("спереди")) return Zone.FRONT;
        if (s.contains("слева")) return Zone.LEFT;
        return s.contains("справа") ? Zone.RIGHT : null;
    }
    private static Set<ObjectType> objects(String s) {
        Set<ObjectType> found = new HashSet<>();
        if (s.contains("качел")) found.add(ObjectType.SWING);
        if (s.contains("горк")) found.add(ObjectType.SLIDE);
        if (s.contains("песочниц")) found.add(ObjectType.SANDBOX);
        if (s.contains("скамейк") || s.contains("лавочк")) found.add(ObjectType.BENCH);
        if (s.contains("контейнер") || s.contains("урн")) found.add(ObjectType.BIN);
        if (s.contains("детск") && s.contains("площадк") && found.isEmpty()) found.add(ObjectType.PLAYGROUND);
        if (s.contains("дорожк") || s.contains("тротуар")) found.add(ObjectType.PATH);
        if (s.contains("парковк") || s.contains("стоянк")) found.add(ObjectType.PARKING);
        return found;
    }
}
