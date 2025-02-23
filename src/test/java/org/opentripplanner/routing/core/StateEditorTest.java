package org.opentripplanner.routing.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.trippattern.Deduplicator;
import org.opentripplanner.transit.service.StopModel;

public class StateEditorTest {

  @Test
  public final void testIncrementTimeInSeconds() {
    Graph graph = new Graph();
    RoutingRequest routingRequest = new RoutingRequest();
    RoutingContext routingContext = new RoutingContext(routingRequest, graph, (Vertex) null, null);
    StateEditor stateEditor = new StateEditor(routingContext, null);

    stateEditor.setTimeSeconds(0);
    stateEditor.incrementTimeInSeconds(999999999);

    assertEquals(999999999, stateEditor.child.getTimeSeconds());
  }

  /**
   * Test update of non transit options.
   */
  @Test
  public final void testSetNonTransitOptionsFromState() {
    RoutingRequest request = new RoutingRequest();
    request.setMode(TraverseMode.CAR);
    request.parkAndRide = true;
    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    var graph = new Graph(stopModel, deduplicator);

    var temporaryVertices = new TemporaryVerticesContainer(graph, request);
    RoutingContext routingContext = new RoutingContext(request, graph, temporaryVertices);
    State state = new State(routingContext);

    state.stateData.vehicleParked = true;
    state.stateData.vehicleRentalState = VehicleRentalState.BEFORE_RENTING;
    state.stateData.currentMode = TraverseMode.WALK;

    StateEditor se = new StateEditor(routingContext, null);
    se.setNonTransitOptionsFromState(state);
    State updatedState = se.makeState();
    assertEquals(TraverseMode.WALK, updatedState.getNonTransitMode());
    assertTrue(updatedState.isVehicleParked());
    assertFalse(updatedState.isRentingVehicle());
    temporaryVertices.close();
  }

  @Test
  public final void testWeightIncrement() {
    Graph graph = new Graph();
    RoutingRequest routingRequest = new RoutingRequest();
    RoutingContext routingContext = new RoutingContext(routingRequest, graph, (Vertex) null, null);
    StateEditor stateEditor = new StateEditor(routingContext, null);

    stateEditor.setTimeSeconds(0);
    stateEditor.incrementWeight(10);

    assertNotNull(stateEditor.makeState());
  }

  @Test
  public final void testNanWeightIncrement() {
    Graph graph = new Graph();
    RoutingRequest routingRequest = new RoutingRequest();
    RoutingContext routingContext = new RoutingContext(routingRequest, graph, (Vertex) null, null);
    StateEditor stateEditor = new StateEditor(routingContext, null);

    stateEditor.setTimeSeconds(0);
    stateEditor.incrementWeight(Double.NaN);

    assertNull(stateEditor.makeState());
  }

  @Test
  public final void testInfinityWeightIncrement() {
    Graph graph = new Graph();
    RoutingRequest routingRequest = new RoutingRequest();
    RoutingContext routingContext = new RoutingContext(routingRequest, graph, (Vertex) null, null);
    StateEditor stateEditor = new StateEditor(routingContext, null);

    stateEditor.setTimeSeconds(0);
    stateEditor.incrementWeight(Double.NEGATIVE_INFINITY);

    assertNull(stateEditor.makeState(), "Infinity weight increment");
  }
}
