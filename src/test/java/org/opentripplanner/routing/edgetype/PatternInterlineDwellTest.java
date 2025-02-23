package org.opentripplanner.routing.edgetype;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.opentripplanner.GtfsTest;
import org.opentripplanner.model.plan.Itinerary;

@Disabled
public class PatternInterlineDwellTest extends GtfsTest {

  @Override
  public String getFeedName() {
    return "gtfs/interlining";
  }

  // TODO Allow using Calendar or ISOdate for testing, interpret it in the given graph's timezone.

  @Test
  public void testInterlining() {
    LocalDateTime ldt = LocalDateTime.of(2014, 1, 1, 0, 5, 0);
    ZonedDateTime zdt = ZonedDateTime.of(ldt, ZoneId.of("America/New_York"));
    long time = zdt.toEpochSecond();
    // We should arrive at the destination using two legs, both of which are on
    // the same route and with zero transfers.
    Itinerary itinerary = plan(time, "stop0", "stop3", null, false, false, null, null, null, 2);

    assertEquals(itinerary.getLegs().get(0).getRoute().getId().getId(), "route1");
    assertEquals(itinerary.getLegs().get(1).getRoute().getId().getId(), "route1");
    assertEquals(0, itinerary.getNumberOfTransfers());
  }
  // TODO test for trips on the same block with no transfer allowed (Trimet special case)

}
