package org.opentripplanner.routing.street;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.routing.core.TraverseMode.BICYCLE;
import static org.opentripplanner.routing.core.TraverseMode.CAR;
import static org.opentripplanner.test.support.PolylineAssert.assertThatPolylinesAreEqual;

import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.locationtech.jts.geom.Geometry;
import org.mockito.Mockito;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.OtpModel;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.WalkStep;
import org.opentripplanner.routing.algorithm.mapping.GraphPathToItineraryMapper;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.TemporaryVerticesContainer;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.standalone.config.RouterConfig;
import org.opentripplanner.standalone.server.Router;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.PolylineEncoder;

public class BarrierRoutingTest {

  private static final Instant dateTime = Instant.now();

  private static Graph graph;

  @BeforeAll
  public static void createGraph() {
    OtpModel otpModel = ConstantsForTests.buildOsmGraph(
      ConstantsForTests.HERRENBERG_BARRIER_GATES_OSM
    );
    graph = otpModel.graph;
  }

  /**
   * Access restrictions on nodes should be taken into account, with walking the bike if needed.
   */
  @Test
  public void shouldWalkForBarriers() {
    var from = new GenericLocation(48.59384, 8.86848);
    var to = new GenericLocation(48.59370, 8.87079);

    // This takes a detour to avoid walking with the bike
    var polyline1 = computePolyline(graph, from, to, BICYCLE);
    assertThatPolylinesAreEqual(polyline1, "o~qgH_ccu@DGFENQZ]NOLOHMFKFILB`BOGo@AeD]U}BaA]Q??");

    // The reluctance for walking with the bike is reduced, so a detour is not taken
    var polyline2 = computePolyline(
      graph,
      from,
      to,
      BICYCLE,
      rr -> rr.bikeWalkingReluctance = 1,
      itineraries ->
        itineraries
          .stream()
          .flatMap(i ->
            Stream.of(
              () -> assertEquals(1, i.getLegs().size()),
              () -> assertEquals(BICYCLE, i.getLegs().get(0).getMode()),
              () ->
                assertEquals(
                  List.of(false, true, false, true, false),
                  i
                    .getLegs()
                    .get(0)
                    .getWalkSteps()
                    .stream()
                    .map(WalkStep::isWalkingBike)
                    .collect(Collectors.toList())
                )
            )
          )
    );
    assertThatPolylinesAreEqual(polyline2, "o~qgH_ccu@Bi@Bk@Bi@Bg@NaA@_@Dm@Dq@a@KJy@@I@M@E??");
  }

  /**
   * Car-only barriers should be driven around.
   */
  @Test
  public void shouldDriveAroundBarriers() {
    var from = new GenericLocation(48.59291, 8.87037);
    var to = new GenericLocation(48.59262, 8.86879);

    // This takes a detour to avoid walking with the bike
    var polyline1 = computePolyline(graph, from, to, CAR);
    assertThatPolylinesAreEqual(polyline1, "sxqgHyncu@ZTnAFRdEyAFPpA");
  }

  @Test
  public void shouldDriveToBarrier() {
    var from = new GenericLocation(48.59291, 8.87037);
    var to = new GenericLocation(48.59276, 8.86963);

    // This takes a detour to avoid walking with the bike
    var polyline1 = computePolyline(graph, from, to, CAR);
    assertThatPolylinesAreEqual(polyline1, "sxqgHyncu@ZT?~B");
  }

  @Test
  public void shouldDriveFromBarrier() {
    var from = new GenericLocation(48.59273, 8.86931);
    var to = new GenericLocation(48.59291, 8.87037);

    // This takes a detour to avoid walking with the bike
    var polyline1 = computePolyline(graph, from, to, CAR);
    assertThatPolylinesAreEqual(polyline1, "qwqgHchcu@BTxAGSeEoAG[U");
  }

  private static String computePolyline(
    Graph graph,
    GenericLocation from,
    GenericLocation to,
    TraverseMode traverseMode
  ) {
    return computePolyline(
      graph,
      from,
      to,
      traverseMode,
      ignored -> {},
      itineraries ->
        itineraries
          .stream()
          .flatMap(i -> i.getLegs().stream())
          .map(l ->
            () -> assertEquals(traverseMode, l.getMode(), "Allow only " + traverseMode + " legs")
          )
    );
  }

  private static String computePolyline(
    Graph graph,
    GenericLocation from,
    GenericLocation to,
    TraverseMode traverseMode,
    Consumer<RoutingRequest> options,
    Function<List<Itinerary>, Stream<Executable>> assertions
  ) {
    RoutingRequest request = new RoutingRequest();
    request.setDateTime(dateTime);
    request.from = from;
    request.to = to;
    request.streetSubRequestModes = new TraverseModeSet(traverseMode);

    options.accept(request);

    var temporaryVertices = new TemporaryVerticesContainer(graph, request);
    RoutingContext routingContext = new RoutingContext(request, graph, temporaryVertices);

    var gpf = new GraphPathFinder(
      new Router(
        graph,
        Mockito.mock(TransitModel.class),
        RouterConfig.DEFAULT,
        Metrics.globalRegistry
      )
    );
    var paths = gpf.graphPathFinderEntryPoint(routingContext);

    GraphPathToItineraryMapper graphPathToItineraryMapper = new GraphPathToItineraryMapper(
      ZoneId.of("Europe/Berlin"),
      graph.streetNotesService,
      graph.ellipsoidToGeoidDifference
    );

    var itineraries = graphPathToItineraryMapper.mapItineraries(paths);

    assertAll(assertions.apply(itineraries));

    Geometry legGeometry = itineraries.get(0).getLegs().get(0).getLegGeometry();
    temporaryVertices.close();

    return PolylineEncoder.encodeGeometry(legGeometry).points();
  }
}
