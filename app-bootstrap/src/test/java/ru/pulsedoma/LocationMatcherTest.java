package ru.pulsedoma;

import org.junit.jupiter.api.Test;
import ru.pulsedoma.duplicates.LocationMatcher;
import static org.junit.jupiter.api.Assertions.*;

class LocationMatcherTest {
    private final LocationMatcher matcher = new LocationMatcher();

    @Test void elevatorIgnoresFloorButRespectsEntranceAndSpecificLift() {
        assertTrue(matcher.compare("ELEVATOR", "подьезд 3, этаж 2", "в третьем подъезде на четвертом этаже").compatible());
        assertFalse(matcher.compare("ELEVATOR", "подъезд 3 этаж 2", "подъезд 4 этаж 2").compatible());
        assertFalse(matcher.compare("ELEVATOR", "подъезд 3 грузовой лифт", "подъезд 3 пассажирский лифт").compatible());
        assertFalse(matcher.compare("ELEVATOR", "подъезд 3 лифт 1", "подъезд 3 лифт 2").compatible());
        assertFalse(matcher.compare("ELEVATOR", "этаж 2", "этаж 4").compatible());
    }

    @Test void indoorCategoriesRespectFloorApartmentAndWordOrder() {
        for (String category : new String[] {"LIGHTING", "WATER", "HEATING", "ENTRANCE_CLEANING"}) {
            assertTrue(matcher.compare(category, "подъезд 3 этаж 2", "второй этаж третьего подъезда").compatible());
            assertFalse(matcher.compare(category, "подъезд 3 этаж 2", "подъезд 3 этаж 4").compatible());
            assertFalse(matcher.compare(category, "подъезд 3 этаж 2 квартира 10", "подъезд 3 этаж 2 квартира 11").compatible());
            assertEquals(.65, matcher.compare(category, "подъезд 3", "подъезд 3 этаж 2").score());
        }
    }

    @Test void outdoorCategoriesRespectObjectAndExplicitZone() {
        for (String category : new String[] {"YARD_CLEANING", "WASTE_REMOVAL", "PLAYGROUND"}) {
            assertTrue(matcher.compare(category, "качели во дворе", "у качелей во дворе").compatible());
            assertFalse(matcher.compare(category, "качели во дворе", "горка во дворе").compatible());
            assertFalse(matcher.compare(category, "контейнер 1", "контейнер 2").compatible());
            assertFalse(matcher.compare(category, "качели за домом", "качели перед домом").compatible());
            assertFalse(matcher.compare(category, "качели у подъезда 1", "качели у подъезда 2").compatible());
        }
    }

    @Test void unrecognizedPlacesRequireExactNormalizedWords() {
        assertFalse(matcher.compare("OTHER", "вход со стороны дороги", "вход со стороны двора").compatible());
        assertTrue(matcher.compare("OTHER", "Подъезд 3, этаж 2", "этаж 2 подъезд 3").compatible());
    }
}
