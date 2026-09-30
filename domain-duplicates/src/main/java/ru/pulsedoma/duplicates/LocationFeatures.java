package ru.pulsedoma.duplicates;

import ru.pulsedoma.common.BusinessException;
import java.util.ArrayList;
import java.util.List;

/** Structured place; apartment numbers are deliberately not collected. */
public record LocationFeatures(Area area, Integer entrance, Integer floor, LiftType liftType,
                               Integer liftNumber, Coverage coverage, OutdoorObject object,
                               Integer site, String details) {
    public enum Area { ENTRANCE, APARTMENT, BASEMENT, YARD, WHOLE_HOUSE, OTHER }
    public enum LiftType { PASSENGER, CARGO, UNKNOWN }
    public enum Coverage { LOCAL, ENTIRE }
    public enum OutdoorObject { SWING, SLIDE, SANDBOX, OTHER, WHOLE_PLAYGROUND }

    public void validate(String category) {
        if (area == null) throw new BusinessException("INVALID_LOCATION", "Выберите место проблемы");
        boolean valid = area != null && positive(entrance, 100) && positive(floor, 200)
                && positive(liftNumber, 100) && positive(site, 100)
                && (details == null || details.length() <= 160);
        valid &= switch (category) {
            case "ELEVATOR" -> area == Area.ENTRANCE && entrance != null && liftType != null
                    && floor == null && coverage == null && object == null && site == null;
            case "LIGHTING" -> (area == Area.YARD || area == Area.ENTRANCE && entrance != null && floor != null)
                    && liftType == null && liftNumber == null && coverage == null && object == null && site == null;
            case "WATER" -> List.of(Area.APARTMENT, Area.ENTRANCE, Area.BASEMENT, Area.YARD).contains(area)
                    && indoorComplete() && noSpecialFields();
            case "HEATING" -> List.of(Area.APARTMENT, Area.ENTRANCE, Area.WHOLE_HOUSE).contains(area)
                    && indoorComplete() && noSpecialFields();
            case "ENTRANCE_CLEANING" -> area == Area.ENTRANCE && entrance != null && coverage != null
                    && (coverage == Coverage.ENTIRE ? floor == null : floor != null)
                    && liftType == null && liftNumber == null && object == null && site == null;
            case "YARD_CLEANING" -> area == Area.YARD && noSpecialFields();
            case "WASTE_REMOVAL" -> area == Area.YARD && liftType == null && liftNumber == null && coverage == null && object == null;
            case "PLAYGROUND" -> area == Area.YARD && liftType == null && liftNumber == null && coverage == null && site == null;
            case "OTHER" -> List.of(Area.ENTRANCE, Area.YARD, Area.BASEMENT, Area.WHOLE_HOUSE, Area.OTHER).contains(area)
                    && (area != Area.ENTRANCE || entrance != null) && noSpecialFields()
                    && (area != Area.OTHER || details != null && !details.isBlank());
            default -> false;
        };
        if (area != Area.ENTRANCE && area != Area.APARTMENT) valid &= entrance == null && floor == null;
        if (!valid) throw new BusinessException("INVALID_LOCATION", "Проверьте поля места для выбранной категории");
    }
    private boolean indoorComplete() {
        return area != Area.APARTMENT && area != Area.ENTRANCE || entrance != null && floor != null;
    }
    private boolean noSpecialFields() { return liftType == null && liftNumber == null && coverage == null && object == null && site == null; }
    private static boolean positive(Integer n, int max) { return n == null || n >= 1 && n <= max; }

    public String label() {
        List<String> parts = new ArrayList<>();
        parts.add(switch (area) {
            case ENTRANCE -> "Подъезд " + entrance;
            case APARTMENT -> "В квартире, подъезд " + entrance;
            case BASEMENT -> "Подвал";
            case YARD -> "Двор";
            case WHOLE_HOUSE -> "Весь дом";
            case OTHER -> "Другое место";
        });
        if (floor != null) parts.add("этаж " + floor);
        if (coverage == Coverage.ENTIRE) parts.add("весь подъезд");
        if (liftType != null) parts.add(switch (liftType) {
            case PASSENGER -> "пассажирский лифт";
            case CARGO -> "грузовой лифт";
            case UNKNOWN -> "лифт, тип не указан";
        });
        if (liftNumber != null) parts.add("лифт № " + liftNumber);
        if (site != null) parts.add("мусорная площадка № " + site);
        if (object != null) parts.add(switch (object) {
            case SWING -> "качели"; case SLIDE -> "горка"; case SANDBOX -> "песочница";
            case OTHER -> "другой объект площадки"; case WHOLE_PLAYGROUND -> "вся детская площадка";
        });
        if (details != null && !details.isBlank()) parts.add(details.strip());
        return String.join(", ", parts);
    }
}
