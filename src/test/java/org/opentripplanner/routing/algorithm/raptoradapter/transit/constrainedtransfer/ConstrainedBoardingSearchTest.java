package org.opentripplanner.routing.algorithm.raptoradapter.transit.constrainedtransfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.model.transfer.TransferConstraint.REGULAR_TRANSFER;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.RAPTOR_STOP_INDEX;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.STATION_B;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.STOP_A;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.STOP_B;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.STOP_C;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.STOP_D;
import static org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestTransitCaseData.stopIndex;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.model.transfer.ConstrainedTransfer;
import org.opentripplanner.model.transfer.RouteStationTransferPoint;
import org.opentripplanner.model.transfer.RouteStopTransferPoint;
import org.opentripplanner.model.transfer.StationTransferPoint;
import org.opentripplanner.model.transfer.StopTransferPoint;
import org.opentripplanner.model.transfer.TransferConstraint;
import org.opentripplanner.model.transfer.TripTransferPoint;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.StopIndexForRaptor;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TransitTuningParameters;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripPatternWithRaptorStopIndexes;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TestRouteData;
import org.opentripplanner.transit.model._data.TransitModelForTest;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.TransitMode;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.raptor.api.transit.RaptorTripScheduleBoardOrAlightEvent;
import org.opentripplanner.util.OTPFeature;

public class ConstrainedBoardingSearchTest {

  private static final FeedScopedId ID = TransitModelForTest.id("ID");
  private static final TransferConstraint GUARANTEED_CONSTRAINT = TransferConstraint
    .create()
    .guaranteed()
    .build();
  private static final TransferConstraint NOT_ALLOWED_CONSTRAINT = TransferConstraint
    .create()
    .notAllowed()
    .build();
  private static final TransferConstraint MIN_TRANSFER_TIME_10_MIN_CONSTRAINT = TransferConstraint
    .create()
    .minTransferTime(600)
    .build();
  private static final TransferConstraint MIN_TRANSFER_TIME_0_MIN_CONSTRAINT = TransferConstraint
    .create()
    .minTransferTime(0)
    .build();
  private static final StopTransferPoint STOP_B_TX_POINT = new StopTransferPoint(STOP_B);
  private static final StopTransferPoint STOP_C_TX_POINT = new StopTransferPoint(STOP_C);

  private static final int TRIP_1_INDEX = 0;
  private static final int TRIP_2_INDEX = 1;
  public static final StationTransferPoint STATION_B_TX_POINT = new StationTransferPoint(STATION_B);

  /**
   * 2 minutes alight slack is used in this test, no slack provider is involved - but the test pass
   * in times to the search with slack added.
   */
  private static final int ALIGHT_BOARD_SLACK = 120;

  private static final int TRANSFER_SLACK = 0;

  private TestRouteData route1;
  private TestRouteData route2;
  private TripPatternWithRaptorStopIndexes pattern1;
  private TripPatternWithRaptorStopIndexes pattern2;
  private StopIndexForRaptor stopIndex;

  /**
   * Create transit data with 2 routes with a trip each.
   * <pre>
   *                              STOPS
   *                     A      B      C      D
   * Route R1
   *   - Trip R1-1:    10:00  10:10  10:20
   *   - Trip R1-2:    10:05  10:15  10:25
   * Route R2
   *   - Trip R2-1:           10:15  10:30  10:40
   *   - Trip R2-2:           10:20  10:35  10:45
   *   - Trip R2-3:           10:25  10:40  10:50
   *   - Trip R2-4:           10:30  10:45  10:55
   *   - Trip R2-5:           10:35  10:50  11:00
   *   - Trip R2-6:           10:40  10:55  11:05
   * </pre>
   * <ul>
   *     <li>
   *         The transfer at stop B is tight between trip R1-2 and R2-1. There is no time between
   *         the arrival and departure, and it is only possible to transfer if the transfer is
   *         stay-seated or guaranteed. For other types of constrained transfers we should board
   *         the next trip 'R2-2'.
   *     </li>
   *     <li>
   *         The transfer at stop C allow regular transfers between trip R1-2 and R2-1.
   *     </li>
   *     <li>
   *         R1-1 is the fallback in the reverse search in the same way as R2-2 is the fallback
   *         int the forward search.
   *     </li>
   * </ul>
   * The
   */
  @BeforeEach
  void setup() {
    route1 =
      new TestRouteData(
        "R1",
        TransitMode.RAIL,
        List.of(STOP_A, STOP_B, STOP_C),
        "10:00 10:10 10:20",
        "10:05 10:15 10:25"
      );

    route2 =
      new TestRouteData(
        "R2",
        TransitMode.BUS,
        List.of(STOP_B, STOP_C, STOP_D),
        "10:15 10:30 10:40",
        "10:20 10:35 10:45",
        "10:25 10:40 10:50",
        "10:30 10:45 10:55",
        "10:35 10:50 11:00",
        "10:40 10:55 11:05"
      );

    this.pattern1 = route1.getRaptorTripPattern();
    this.pattern2 = route2.getRaptorTripPattern();
    this.stopIndex =
      new StopIndexForRaptor(List.of(RAPTOR_STOP_INDEX), TransitTuningParameters.FOR_TEST);
  }

  @Test
  void transferExist() {
    int fromStopPos = route1.stopPosition(STOP_C);
    int toStopPos = route2.stopPosition(STOP_C);

    var txAllowed = new ConstrainedTransfer(
      ID,
      STOP_C_TX_POINT,
      STOP_C_TX_POINT,
      GUARANTEED_CONSTRAINT
    );
    generateTransfersForPatterns(List.of(txAllowed));

    // Forward
    var subject = route2.getRaptorTripPattern().constrainedTransferForwardSearch();
    assertTrue(subject.transferExist(toStopPos));

    // Reverse
    subject = route1.getRaptorTripPattern().constrainedTransferReverseSearch();
    assertTrue(subject.transferExist(fromStopPos));
  }

  @Test
  void findGuaranteedTransferWithZeroConnectionTimeWithStation() {
    var txGuaranteedTrip2Trip = new ConstrainedTransfer(
      ID,
      STATION_B_TX_POINT,
      STATION_B_TX_POINT,
      GUARANTEED_CONSTRAINT
    );
    findGuaranteedTransferWithZeroConnectionTime(List.of(txGuaranteedTrip2Trip));
  }

  @Test
  void findGuaranteedTransferWithZeroConnectionTimeWithStop() {
    var txGuaranteedTrip2Trip = new ConstrainedTransfer(
      ID,
      STOP_B_TX_POINT,
      STOP_B_TX_POINT,
      GUARANTEED_CONSTRAINT
    );
    findGuaranteedTransferWithZeroConnectionTime(List.of(txGuaranteedTrip2Trip));
  }

  @Test
  void findGuaranteedTransferWithZeroConnectionTimeWithRouteAndStopTransfers() {
    var route1TxPoint = new RouteStopTransferPoint(route1.getRoute(), STOP_B);
    var route2TxPoint = new RouteStopTransferPoint(route2.getRoute(), STOP_B);

    var txGuaranteedTrip2Trip = new ConstrainedTransfer(
      ID,
      route1TxPoint,
      route2TxPoint,
      GUARANTEED_CONSTRAINT
    );
    findGuaranteedTransferWithZeroConnectionTime(List.of(txGuaranteedTrip2Trip));
  }

  @Test
  void findGuaranteedTransferWithZeroConnectionTimeWithRouteAndStationTransfers() {
    var route1TxPoint = new RouteStationTransferPoint(route1.getRoute(), STATION_B);
    var route2TxPoint = new RouteStationTransferPoint(route2.getRoute(), STATION_B);

    var txGuaranteedTrip2Trip = new ConstrainedTransfer(
      ID,
      route1TxPoint,
      route2TxPoint,
      GUARANTEED_CONSTRAINT
    );
    findGuaranteedTransferWithZeroConnectionTime(List.of(txGuaranteedTrip2Trip));
  }

  @Test
  void findGuaranteedTransferWithZeroConnectionTimeWithTripTransfers() {
    int sourceStopPos = route1.stopPosition(STOP_B);
    int targetStopPos = route2.stopPosition(STOP_B);
    var trip1TxPoint = new TripTransferPoint(route1.lastTrip().trip(), sourceStopPos);
    var trip2TxPoint = new TripTransferPoint(route2.firstTrip().trip(), targetStopPos);

    var txGuaranteedTrip2Trip = new ConstrainedTransfer(
      ID,
      trip1TxPoint,
      trip2TxPoint,
      GUARANTEED_CONSTRAINT
    );
    findGuaranteedTransferWithZeroConnectionTime(List.of(txGuaranteedTrip2Trip));
  }

  @Test
  void findGuaranteedTransferWithMostSpecificTransfers() {
    int sourceStopPos = route1.stopPosition(STOP_B);
    int targetStopPos = route2.stopPosition(STOP_B);
    var trip1TxPoint = new TripTransferPoint(route1.lastTrip().trip(), sourceStopPos);
    var route1TxPoint = new RouteStopTransferPoint(route1.getRoute(), STOP_B);
    var trip2TxPoint = new TripTransferPoint(route2.firstTrip().trip(), targetStopPos);

    var transfers = List.of(
      new ConstrainedTransfer(ID, STOP_B_TX_POINT, trip2TxPoint, NOT_ALLOWED_CONSTRAINT),
      new ConstrainedTransfer(ID, trip1TxPoint, STOP_B_TX_POINT, GUARANTEED_CONSTRAINT),
      new ConstrainedTransfer(ID, route1TxPoint, STOP_B_TX_POINT, NOT_ALLOWED_CONSTRAINT)
    );
    findGuaranteedTransferWithZeroConnectionTime(transfers);
  }

  @Test
  void findNextTransferWhenFirstTransferIsNotAllowed() {
    int sourceStopPos = route1.stopPosition(STOP_C);
    int targetStopPos = route2.stopPosition(STOP_C);
    var trip1TxPoint = new TripTransferPoint(route1.lastTrip().trip(), sourceStopPos);
    var trip2TxPoint = new TripTransferPoint(route2.firstTrip().trip(), targetStopPos);

    var txNotAllowed = new ConstrainedTransfer(
      ID,
      trip1TxPoint,
      trip2TxPoint,
      NOT_ALLOWED_CONSTRAINT
    );

    testTransferSearch(STOP_C, List.of(txNotAllowed), TRIP_2_INDEX, TRIP_1_INDEX, REGULAR_TRANSFER);
  }

  @Test
  void blockTransferWhenNotAllowedApplyToAllTrips() {
    ConstrainedTransfer transfer = new ConstrainedTransfer(
      ID,
      STOP_C_TX_POINT,
      STOP_C_TX_POINT,
      NOT_ALLOWED_CONSTRAINT
    );
    testTransferSearch(
      STOP_C,
      List.of(transfer),
      TRIP_1_INDEX,
      TRIP_2_INDEX,
      NOT_ALLOWED_CONSTRAINT
    );
  }

  @Test
  void makeSureTheSearchIsAbortedAfter5NormalTripsAreFound() {
    int sourceStopPos = route1.stopPosition(STOP_C);
    int targetStopPos = route2.stopPosition(STOP_C);
    var trip1TxPoint = new TripTransferPoint(route1.lastTrip().trip(), sourceStopPos);
    var trip2TxPoint = new TripTransferPoint(route2.lastTrip().trip(), targetStopPos);

    var txGuaranteed = new ConstrainedTransfer(
      ID,
      trip1TxPoint,
      trip2TxPoint,
      GUARANTEED_CONSTRAINT
    );

    testTransferSearch(STOP_C, List.of(txGuaranteed), TRIP_2_INDEX, TRIP_1_INDEX, null);
  }

  @Test
  void findMinimumTimeTransfer() {
    var txMinTransferTime = new ConstrainedTransfer(
      ID,
      STOP_C_TX_POINT,
      STOP_C_TX_POINT,
      MIN_TRANSFER_TIME_10_MIN_CONSTRAINT
    );

    testTransferSearch(
      STOP_C,
      List.of(txMinTransferTime),
      TRIP_2_INDEX,
      TRIP_1_INDEX,
      MIN_TRANSFER_TIME_10_MIN_CONSTRAINT
    );
  }

  @Test
  void findDefinitiveMinTimeTransfer() {
    // we set a very low minimum transfer time of 0 seconds. we expect this to work similar
    // to a guaranteed transfer and hence it has the same expectation.
    OTPFeature.enableFeatures(Map.of(OTPFeature.MinimumTransferTimeIsDefinitive, true));
    var txMinTransferTime = new ConstrainedTransfer(
      ID,
      STOP_B_TX_POINT,
      STOP_B_TX_POINT,
      MIN_TRANSFER_TIME_0_MIN_CONSTRAINT
    );

    testTransferSearch(
      STOP_B,
      List.of(txMinTransferTime),
      TRIP_1_INDEX,
      TRIP_2_INDEX,
      MIN_TRANSFER_TIME_0_MIN_CONSTRAINT
    );
    OTPFeature.enableFeatures(Map.of(OTPFeature.MinimumTransferTimeIsDefinitive, false));
  }

  void testTransferSearch(
    Stop transferStop,
    List<ConstrainedTransfer> constraints,
    int expTripIndexFwdSearch,
    int expTripIndexRevSearch,
    TransferConstraint expConstraint
  ) {
    testTransferSearchForward(transferStop, constraints, expTripIndexFwdSearch, expConstraint);
    testTransferSearchReverse(transferStop, constraints, expTripIndexRevSearch, expConstraint);
  }

  void testTransferSearchForward(
    Stop transferStop,
    List<ConstrainedTransfer> txList,
    int expectedTripIndex,
    TransferConstraint expectedConstraint
  ) {
    generateTransfersForPatterns(txList);
    var subject = pattern2.constrainedTransferForwardSearch();

    int targetStopPos = route2.stopPosition(transferStop);
    int stopIndex = stopIndex(transferStop);
    int sourceArrivalTime = route1.lastTrip().getStopTime(transferStop).getArrivalTime();

    // Check that transfer exist
    assertTrue(subject.transferExist(targetStopPos));

    var boarding = subject.find(
      route2.getTimetable(),
      TRANSFER_SLACK,
      route1.lastTrip().getTripSchedule(),
      stopIndex,
      sourceArrivalTime,
      sourceArrivalTime + ALIGHT_BOARD_SLACK
    );
    assertBoarding(stopIndex, targetStopPos, expectedTripIndex, expectedConstraint, boarding);
  }

  void testTransferSearchReverse(
    Stop transferStop,
    List<ConstrainedTransfer> txList,
    int expectedTripIndex,
    TransferConstraint expectedConstraint
  ) {
    generateTransfersForPatterns(txList);
    var subject = pattern1.constrainedTransferReverseSearch();
    int targetStopPos = route1.stopPosition(transferStop);

    int stopIndex = stopIndex(transferStop);
    int sourceArrivalTime = route2.firstTrip().getStopTime(transferStop).getDepartureTime();

    // Check that transfer exist
    assertTrue(subject.transferExist(targetStopPos));

    var boarding = subject.find(
      route1.getTimetable(),
      TRANSFER_SLACK,
      route2.firstTrip().getTripSchedule(),
      stopIndex,
      sourceArrivalTime,
      sourceArrivalTime - (2 * ALIGHT_BOARD_SLACK + TRANSFER_SLACK)
    );

    assertBoarding(stopIndex, targetStopPos, expectedTripIndex, expectedConstraint, boarding);
  }

  /**
   * The most specific transfer passed in should be a guaranteed transfer at stop B
   */
  private void findGuaranteedTransferWithZeroConnectionTime(
    List<ConstrainedTransfer> constrainedTransfers
  ) {
    testTransferSearch(
      STOP_B,
      constrainedTransfers,
      TRIP_1_INDEX,
      TRIP_2_INDEX,
      GUARANTEED_CONSTRAINT
    );
  }

  private void assertBoarding(
    int stopIndex,
    int targetStopPos,
    int expectedTripIndex,
    TransferConstraint expectedConstraint,
    RaptorTripScheduleBoardOrAlightEvent<TripSchedule> boarding
  ) {
    if (expectedConstraint != null) {
      assertNotNull(boarding);
      assertEquals(expectedConstraint, boarding.getTransferConstraint());
      assertEquals(stopIndex, boarding.getBoardStopIndex());
      assertEquals(targetStopPos, boarding.getStopPositionInPattern());
      assertEquals(expectedTripIndex, boarding.getTripIndex());
    } else {
      assertNull(boarding);
    }
  }

  private void generateTransfersForPatterns(Collection<ConstrainedTransfer> txList) {
    new TransferIndexGenerator(txList, List.of(pattern1, pattern2), stopIndex).generateTransfers();
  }
}
