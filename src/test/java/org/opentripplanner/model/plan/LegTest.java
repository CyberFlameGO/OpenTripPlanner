package org.opentripplanner.model.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

public class LegTest implements PlanTestConstants {

  private final Itinerary ITINERARY = TestItineraryBuilder
    .newItinerary(A, T11_00)
    .walk(D2m, B)
    .bus(21, T11_05, T11_15, C)
    .bicycle(T11_16, T11_20, E)
    .build();

  private final Leg WALK_LEG = ITINERARY.firstLeg();
  private final Leg BUS_LEG = ITINERARY.getLegs().get(1);
  private final Leg BICYCLE_LEG = ITINERARY.lastLeg();

  @Test
  public void isTransitLeg() {
    assertFalse(WALK_LEG.isTransitLeg());
    assertTrue(BUS_LEG.isTransitLeg());
    assertFalse(BICYCLE_LEG.isTransitLeg());
  }

  @Test
  public void isWalkingLeg() {
    assertTrue(WALK_LEG.isWalkingLeg());
    assertFalse(BUS_LEG.isWalkingLeg());
    assertFalse(BICYCLE_LEG.isWalkingLeg());
  }

  @Test
  public void isOnStreetNonTransit() {
    assertTrue(WALK_LEG.isOnStreetNonTransit());
    assertFalse(BUS_LEG.isOnStreetNonTransit());
    assertTrue(BICYCLE_LEG.isOnStreetNonTransit());
  }

  @Test
  public void getDuration() {
    assertEquals(120, WALK_LEG.getDuration());
    assertEquals(600, BUS_LEG.getDuration());
    assertEquals(240, BICYCLE_LEG.getDuration());
  }

  @Test
  public void isPartiallySameTransitLeg() {
    final int fromStopIndex = 2;
    final int toStopIndex = 12;
    final int tripId0 = 33;
    final int tripIdOther = 44;
    final LocalDate day1 = LocalDate.of(2020, 9, 17);
    final LocalDate day2 = LocalDate.of(2020, 9, 18);

    Leg t0 = createLegIgnoreTime(tripId0, fromStopIndex, toStopIndex, day1);
    Leg t1;

    assertTrue(t0.isPartiallySameTransitLeg(t0), "t0 is same trip as it self");

    // Create a new trip with the same trip Id which ride only between the two first stops of trip 0.
    t1 = createLegIgnoreTime(tripId0, fromStopIndex, fromStopIndex + 1, day1);
    assertTrue(t1.isPartiallySameTransitLeg(t0), "t1 overlap t0");
    assertTrue(t0.isPartiallySameTransitLeg(t1), "t0 overlap t1");

    // Create a new trip with the same trip Id but on a diffrent service day
    t1 = createLegIgnoreTime(tripId0, fromStopIndex, fromStopIndex + 1, day2);
    assertFalse(t1.isPartiallySameTransitLeg(t0), "t1 diffrent serviceDate from t0");
    assertFalse(t0.isPartiallySameTransitLeg(t1), "t0 diffrent serviceDate from t1");

    // Create a new trip with the same trip Id which ride only between the two last stops of trip 0.
    t1 = createLegIgnoreTime(tripId0, toStopIndex - 1, toStopIndex, day1);
    assertTrue(t1.isPartiallySameTransitLeg(t0), "t1 overlap t0");
    assertTrue(t0.isPartiallySameTransitLeg(t1), "t0 overlap t1");

    // Create a new trip which alight at the board stop of t0 - should not overlap
    t1 = createLegIgnoreTime(tripId0, fromStopIndex - 1, fromStopIndex, day1);
    assertFalse(t1.isPartiallySameTransitLeg(t0), "t1 do not overlap t0");
    assertFalse(t0.isPartiallySameTransitLeg(t1), "t0 do not overlap t1");

    // Two legs do NOT overlap is on diffrent trips
    t1 = createLegIgnoreTime(tripIdOther, fromStopIndex, toStopIndex, day1);
    assertFalse(t1.isPartiallySameTransitLeg(t0), "t1 do not overlap t0");
    assertFalse(t0.isPartiallySameTransitLeg(t1), "t0 do not overlap t1");
  }

  private static Leg createLegIgnoreTime(
    int tripId,
    int fromStopIndex,
    int toStopIndex,
    LocalDate service
  ) {
    return TestItineraryBuilder
      .newItinerary(A, 99)
      .bus(tripId, 99, 99, fromStopIndex, toStopIndex, B, service)
      .build()
      .firstLeg();
  }
}
