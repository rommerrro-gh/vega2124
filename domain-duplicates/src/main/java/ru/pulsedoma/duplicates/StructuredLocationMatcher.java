package ru.pulsedoma.duplicates;

import java.util.ArrayList;
import java.util.List;
import static ru.pulsedoma.duplicates.LocationFeatures.*;

public final class StructuredLocationMatcher {
    private static final LocationMatcher.Match DIFFERENT = new LocationMatcher.Match(false, 0, List.of());
    public LocationMatcher.Match compare(String category, LocationFeatures a, LocationFeatures b) {
        if (conflict(a.entrance(), b.entrance()) || conflict(a.liftNumber(), b.liftNumber()) || conflict(a.site(), b.site())) return DIFFERENT;
        List<String> reasons = new ArrayList<>(List.of("structured_location"));
        if (a.area() == Area.WHOLE_HOUSE || b.area() == Area.WHOLE_HOUSE) {
            return match(a.area() == b.area() ? 1 : .55, reasons, "whole_house_scope");
        }
        if (a.area() != b.area()) return DIFFERENT;
        if (a.area() == Area.OTHER) {
            var legacy = new LocationMatcher().compare(category, a.details(), b.details());
            return legacy.compatible() ? match(legacy.score(), reasons, "same_location") : DIFFERENT;
        }
        if (a.entrance() != null && a.entrance().equals(b.entrance())) reasons.add("same_entrance");
        if (category.equals("ELEVATOR")) {
            if (a.liftType() != LiftType.UNKNOWN && b.liftType() != LiftType.UNKNOWN && conflict(a.liftType(), b.liftType())) return DIFFERENT;
            boolean known = a.liftType() != null && b.liftType() != null && a.liftType() != LiftType.UNKNOWN && b.liftType() != LiftType.UNKNOWN;
            return match(known ? 1 : .8, reasons, known ? "same_lift_type" : "lift_type_unspecified");
        }
        if (category.equals("ENTRANCE_CLEANING") && (a.coverage() == Coverage.ENTIRE || b.coverage() == Coverage.ENTIRE)) {
            return match(a.coverage() == b.coverage() ? 1 : .65, reasons, "whole_entrance_scope");
        }
        if (conflict(a.floor(), b.floor())) return DIFFERENT;
        if (a.floor() != null && a.floor().equals(b.floor())) return match(1, reasons, "same_floor");
        if (a.area() == Area.YARD) {
            if (category.equals("PLAYGROUND")) {
                boolean specificA = specific(a.object()), specificB = specific(b.object());
                if (specificA && specificB && a.object() != b.object()) return DIFFERENT;
                return match(specificA && specificB ? 1 : .45, reasons,
                        specificA && specificB ? "same_outdoor_object" : "outdoor_place_unspecified");
            }
            if (category.equals("WASTE_REMOVAL") && a.site() != null && a.site().equals(b.site())) return match(1, reasons, "same_waste_site");
            return match(.45, reasons, "outdoor_place_unspecified");
        }
        return match(a.area() == Area.BASEMENT ? 1 : .6, reasons, "same_location");
    }
    private static boolean specific(OutdoorObject o) { return o != null && o != OutdoorObject.OTHER && o != OutdoorObject.WHOLE_PLAYGROUND; }
    private static boolean conflict(Object a, Object b) { return a != null && b != null && !a.equals(b); }
    private static LocationMatcher.Match match(double score, List<String> reasons, String reason) {
        reasons.add(reason); return new LocationMatcher.Match(true, score, List.copyOf(reasons));
    }
}
