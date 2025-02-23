package org.opentripplanner.routing.algorithm.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.common.geometry.DirectionUtils;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.model.VehicleRentalStationInfo;
import org.opentripplanner.model.plan.RelativeDirection;
import org.opentripplanner.model.plan.WalkStep;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.AreaEdge;
import org.opentripplanner.routing.edgetype.ElevatorAlightEdge;
import org.opentripplanner.routing.edgetype.FreeEdge;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.services.notes.StreetNotesService;
import org.opentripplanner.routing.vertextype.ExitVertex;
import org.opentripplanner.routing.vertextype.VehicleRentalPlaceVertex;
import org.opentripplanner.transit.model.basic.WgsCoordinate;

/**
 * Process a list of states into a list of walking/driving instructions for a street leg.
 */
public class StatesToWalkStepsMapper {

  /**
   * Tolerance for how many meters can be between two consecutive turns will be merged into a singe
   * walk step. See {@link StatesToWalkStepsMapper#removeZag(WalkStep, WalkStep)}
   */
  private static final double MAX_ZAG_DISTANCE = 30;

  private final double ellipsoidToGeoidDifference;
  private final StreetNotesService streetNotesService;

  private final List<State> states;
  private final WalkStep previous;
  private final List<WalkStep> steps = new ArrayList<>();

  private WalkStep current = null;
  private double lastAngle = 0;

  /**
   * Distance used for appending elevation profiles
   */
  private double distance = 0;

  /**
   * Track whether we are in a roundabout, and if so the exit number
   */
  private int roundaboutExit = 0;
  private String roundaboutPreviousStreet = null;

  /**
   * Converts a list of street edges to a list of turn-by-turn directions.
   *
   * @param previousStep the last walking step of a non-transit leg that immediately precedes this
   *                     one or null, if first leg
   */
  public StatesToWalkStepsMapper(
    List<State> states,
    WalkStep previousStep,
    StreetNotesService streetNotesService,
    double ellipsoidToGeoidDifference
  ) {
    this.states = states;
    this.previous = previousStep;
    this.streetNotesService = streetNotesService;
    this.ellipsoidToGeoidDifference = ellipsoidToGeoidDifference;
  }

  public static String getNormalizedName(String streetName) {
    if (streetName == null) {
      return null; //Avoid null reference exceptions with pathways which don't have names
    }
    int idx = streetName.indexOf('(');
    if (idx > 0) {
      return streetName.substring(0, idx - 1);
    }
    return streetName;
  }

  public List<WalkStep> generateWalkSteps() {
    for (int i = 0; i < states.size() - 1; i++) {
      processState(states.get(i), states.get(i + 1));
    }

    if (steps.isEmpty()) {
      return steps;
    }

    // add vehicle rental information if applicable
    if (GraphPathToItineraryMapper.isRentalPickUp(states.get(states.size() - 1))) {
      VehicleRentalPlaceVertex vertex = (VehicleRentalPlaceVertex) (
        states.get(states.size() - 1)
      ).getVertex();
      steps.get(steps.size() - 1).setVehicleRentalOffStation(new VehicleRentalStationInfo(vertex));
    }
    if (GraphPathToItineraryMapper.isRentalDropOff(states.get(0))) {
      VehicleRentalPlaceVertex vertex = (VehicleRentalPlaceVertex) (states.get(0)).getVertex();
      steps.get(0).setVehicleRentalOffStation(new VehicleRentalStationInfo(vertex));
    }

    return steps;
  }

  /**
   * Have we done a U-Turn with the previous two states
   */
  private static boolean isUTurn(WalkStep twoBack, WalkStep lastStep) {
    RelativeDirection d1 = lastStep.getRelativeDirection();
    RelativeDirection d2 = twoBack.getRelativeDirection();
    return (
      (
        (d1 == RelativeDirection.RIGHT || d1 == RelativeDirection.HARD_RIGHT) &&
        (d2 == RelativeDirection.RIGHT || d2 == RelativeDirection.HARD_RIGHT)
      ) ||
      (
        (d1 == RelativeDirection.LEFT || d1 == RelativeDirection.HARD_LEFT) &&
        (d2 == RelativeDirection.LEFT || d2 == RelativeDirection.HARD_LEFT)
      )
    );
  }

  private static double getAbsoluteAngleDiff(double thisAngle, double lastAngle) {
    double angleDiff = thisAngle - lastAngle;
    if (angleDiff < 0) {
      angleDiff += Math.PI * 2;
    }
    double ccwAngleDiff = Math.PI * 2 - angleDiff;
    if (ccwAngleDiff < angleDiff) {
      angleDiff = ccwAngleDiff;
    }
    return angleDiff;
  }

  private static boolean isLink(Edge edge) {
    return (
      edge instanceof StreetEdge &&
      (((StreetEdge) edge).getStreetClass() & StreetEdge.CLASS_LINK) == StreetEdge.CLASS_LINK
    );
  }

  private static List<P2<Double>> encodeElevationProfile(
    Edge edge,
    double distanceOffset,
    double heightOffset
  ) {
    if (!(edge instanceof StreetEdge elevEdge)) {
      return new ArrayList<>();
    }
    if (elevEdge.getElevationProfile() == null) {
      return new ArrayList<>();
    }
    ArrayList<P2<Double>> out = new ArrayList<>();
    for (Coordinate coordinate : elevEdge.getElevationProfile().toCoordinateArray()) {
      out.add(new P2<>(coordinate.x + distanceOffset, coordinate.y + heightOffset));
    }
    return out;
  }

  private void processState(State backState, State forwardState) {
    Edge edge = forwardState.getBackEdge();

    boolean createdNewStep = false;
    if (edge instanceof FreeEdge) {
      return;
    }
    if (forwardState.getBackMode() == null) {
      return;
    }
    Geometry geom = edge.getGeometry();
    if (geom == null) {
      return;
    }

    // generate a step for getting off an elevator (all elevator narrative generation occurs
    // when alighting). We don't need to know what came before or will come after
    if (edge instanceof ElevatorAlightEdge) {
      createElevatorWalkStep(backState, forwardState, edge);
      return;
    }

    String streetName = edge.getName().toString();
    String streetNameNoParens = getNormalizedName(streetName);

    boolean modeTransition = forwardState.getBackMode() != backState.getBackMode();

    if (current == null) {
      createFirstStep(backState, forwardState);
      createdNewStep = true;
    } else if (
      modeTransition ||
      !continueOnSameStreet(edge, streetNameNoParens) ||
      // went on to or off of a roundabout
      edge.isRoundabout() !=
      (roundaboutExit > 0) ||
      isLink(edge) &&
      !isLink(backState.getBackEdge())
    ) {
      // Street name has changed, or we've gone on to or off of a roundabout.

      // if we were just on a roundabout, make note of which exit was taken in the existing step
      if (roundaboutExit > 0) {
        // ordinal numbers from
        current.setExit(Integer.toString(roundaboutExit));
        if (streetNameNoParens.equals(roundaboutPreviousStreet)) {
          current.setStayOn(true);
        }
        roundaboutExit = 0;
      }

      // start a new step
      current = createWalkStep(forwardState, backState);
      createdNewStep = true;
      steps.add(current);

      // indicate that we are now on a roundabout and use one-based exit numbering
      if (edge.isRoundabout()) {
        roundaboutExit = 1;
        roundaboutPreviousStreet = getNormalizedName(backState.getBackEdge().getName().toString());
      }

      double thisAngle = DirectionUtils.getFirstAngle(geom);
      current.setDirections(lastAngle, thisAngle, edge.isRoundabout());
      // new step, set distance to length of first edge
      distance = edge.getDistanceMeters();
    } else {
      // street name has not changed
      double thisAngle = DirectionUtils.getFirstAngle(geom);
      RelativeDirection direction = RelativeDirection.calculate(
        lastAngle,
        thisAngle,
        edge.isRoundabout()
      );
      if (edge.isRoundabout()) {
        // we are on a roundabout, and have already traversed at least one edge of it.
        if (multipleTurnOptionsInPreviousState(backState)) {
          // increment exit count if we passed one.
          roundaboutExit += 1;
        }
      } else if (direction != RelativeDirection.CONTINUE) {
        // we are not on a roundabout, and not continuing straight through.
        // figure out if there were other plausible turn options at the last intersection
        // to see if we should generate a "left to continue" instruction.
        if (isPossibleToTurnToOtherStreet(backState, edge, streetName, thisAngle)) {
          // turn to stay on same-named street
          current = createWalkStep(forwardState, backState);
          createdNewStep = true;
          steps.add(current);
          current.setDirections(lastAngle, thisAngle, false);
          current.setStayOn(true);
          // new step, set distance to length of first edge
          distance = edge.getDistanceMeters();
        }
      }
    }

    setMotorwayExit(backState);

    if (createdNewStep && !modeTransition) {
      // check last three steps for zag
      int lastIndex = steps.size() - 1;
      if (lastIndex >= 2) {
        WalkStep threeBack = steps.get(lastIndex - 2);
        WalkStep twoBack = steps.get(lastIndex - 1);
        WalkStep lastStep = steps.get(lastIndex);
        boolean isOnSameStreet = lastStep
          .streetNameNoParens()
          .equals(threeBack.streetNameNoParens());
        if (twoBack.getDistance() < MAX_ZAG_DISTANCE && isOnSameStreet) {
          if (isUTurn(twoBack, lastStep)) {
            steps.remove(lastIndex - 1);
            processUTurn(lastStep, twoBack);
          } else {
            // total hack to remove zags.
            steps.remove(lastIndex);
            steps.remove(lastIndex - 1);
            removeZag(threeBack, twoBack);
          }
        }
      }
    } else {
      if (!createdNewStep && current.getElevation() != null) {
        updateElevationProfile(backState, edge);
      }
      distance += edge.getDistanceMeters();
    }

    // increment the total length for this step
    current.addDistance(edge.getDistanceMeters());
    current.addStreetNotes(streetNotesService.getNotes(forwardState));
    lastAngle = DirectionUtils.getLastAngle(geom);

    current.getEdges().add(edge);
  }

  private void updateElevationProfile(State backState, Edge edge) {
    List<P2<Double>> s = encodeElevationProfile(
      edge,
      distance,
      backState.getOptions().geoidElevation ? -ellipsoidToGeoidDifference : 0
    );
    current.addElevation(s);
  }

  /**
   * Merge two consecutive turns will be into a singe walk step.
   * <pre>
   *      | a
   *      |
   *  ____/
   * / ^ this is a zag between walk steps a and b. If it is less than 30 meters, a and b will be
   * |   in the same walk step.
   * | b
   * </pre>
   */
  private void removeZag(WalkStep threeBack, WalkStep twoBack) {
    current = threeBack;
    current.addDistance(twoBack.getDistance());
    distance += current.getDistance();
    if (twoBack.getElevation() != null) {
      if (current.getElevation() == null) {
        current.addElevation(twoBack.getElevation());
      } else {
        current.addElevation(
          twoBack
            .getElevation()
            .stream()
            .map(p -> new P2<>(p.first + current.getDistance(), p.second))
            .toList()
        );
      }
    }
  }

  private void processUTurn(WalkStep lastStep, WalkStep twoBack) {
    // in this case, we have two left turns or two right turns in quick
    // succession; this is probably a U-turn.

    lastStep.addDistance(twoBack.getDistance());

    // A U-turn to the left, typical in the US.
    if (
      lastStep.getRelativeDirection() == RelativeDirection.LEFT ||
      lastStep.getRelativeDirection() == RelativeDirection.HARD_LEFT
    ) {
      lastStep.setRelativeDirection(RelativeDirection.UTURN_LEFT);
    } else {
      lastStep.setRelativeDirection(RelativeDirection.UTURN_RIGHT);
    }

    // in this case, we're definitely staying on the same street
    // (since it's zag removal, the street names are the same)
    lastStep.setStayOn(true);
  }

  /**
   * Update the walk step with the name of the motorway junction if set from OSM
   */
  private void setMotorwayExit(State backState) {
    State exitState = backState;
    Edge exitEdge = exitState.getBackEdge();
    while (exitEdge instanceof FreeEdge) {
      exitState = exitState.getBackState();
      exitEdge = exitState.getBackEdge();
    }
    if (exitState.getVertex() instanceof ExitVertex) {
      current.setExit(((ExitVertex) exitState.getVertex()).getExitName());
    }
  }

  /**
   * Is it possible to turn to another street from this previous state
   */
  private boolean isPossibleToTurnToOtherStreet(
    State backState,
    Edge edge,
    String streetName,
    double thisAngle
  ) {
    if (edge instanceof StreetEdge) {
      // the next edges will be PlainStreetEdges, we hope
      double angleDiff = getAbsoluteAngleDiff(thisAngle, lastAngle);
      for (StreetEdge alternative : backState.getVertex().getOutgoingStreetEdges()) {
        if (isTurnToOtherStreet(streetName, angleDiff, alternative)) {
          return true;
        }
      }
    } else {
      double angleDiff = getAbsoluteAngleDiff(lastAngle, thisAngle);
      // FIXME: this code might be wrong with the removal of the edge-based graph
      State twoStatesBack = backState.getBackState();
      Vertex backVertex = twoStatesBack.getVertex();
      for (StreetEdge alternative : backVertex.getOutgoingStreetEdges()) {
        for (StreetEdge innerAlternative : alternative.getToVertex().getOutgoingStreetEdges()) {
          if (isTurnToOtherStreet(streetName, angleDiff, innerAlternative)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Is it possible to turn to another street from this alternative edge
   */
  private boolean isTurnToOtherStreet(String streetName, double angleDiff, Edge alternative) {
    if (alternative.getName().toString().equals(streetName)) {
      // alternatives that have the same name
      // are usually caused by street splits
      return false;
    }

    double altAngle = DirectionUtils.getFirstAngle(alternative.getGeometry());
    double altAngleDiff = getAbsoluteAngleDiff(altAngle, lastAngle);
    return angleDiff > Math.PI / 4 || altAngleDiff - angleDiff < Math.PI / 16;
  }

  private boolean continueOnSameStreet(Edge edge, String streetNameNoParens) {
    return !(
      current.getStreetName().toString() != null &&
      !(java.util.Objects.equals(current.streetNameNoParens(), streetNameNoParens)) &&
      (!current.getBogusName() || !edge.hasBogusName())
    );
  }

  private static boolean multipleTurnOptionsInPreviousState(State state) {
    boolean foundAlternatePaths = false;
    TraverseMode requestedMode = state.getNonTransitMode();
    for (Edge out : state.getBackState().getVertex().getOutgoing()) {
      if (out == state.backEdge) {
        continue;
      }
      if (!(out instanceof StreetEdge)) {
        continue;
      }
      State outState = out.traverse(state.getBackState());
      if (outState == null) {
        continue;
      }
      if (!outState.getBackMode().equals(requestedMode)) {
        //walking a bike, so, not really an exit
        continue;
      }
      // this section handles the case of an option which is only an option if you walk your
      // bike. It is complicated because you will not need to walk your bike until one
      // edge after the current edge.

      //now, from here, try a continuing path.
      Vertex tov = outState.getVertex();
      boolean found = false;
      for (Edge out2 : tov.getOutgoing()) {
        State outState2 = out2.traverse(outState);
        if (outState2 != null && !Objects.equals(outState2.getBackMode(), requestedMode)) {
          // walking a bike, so, not really an exit
          continue;
        }
        found = true;
        break;
      }
      if (!found) {
        continue;
      }

      // there were paths we didn't take.
      foundAlternatePaths = true;
      break;
    }
    return foundAlternatePaths;
  }

  private void createFirstStep(State backState, State forwardState) {
    current = createWalkStep(forwardState, backState);
    steps.add(current);

    Edge edge = forwardState.getBackEdge();
    double thisAngle = DirectionUtils.getFirstAngle(edge.getGeometry());
    if (previous == null) {
      current.setAbsoluteDirection(thisAngle);
      current.setRelativeDirection(RelativeDirection.DEPART);
    } else {
      current.setDirections(previous.getAngle(), thisAngle, false);
    }
    // new step, set distance to length of first edge
    distance = edge.getDistanceMeters();
  }

  private void createElevatorWalkStep(State backState, State forwardState, Edge edge) {
    // don't care what came before or comes after
    current = createWalkStep(forwardState, backState);

    // tell the user where to get off the elevator using the exit notation, so the
    // i18n interface will say 'Elevator to <exit>'
    // what happens is that the webapp sees name == null and ignores that, and it sees
    // exit != null and uses to <exit>
    // the floor name is the AlightEdge name
    // reset to avoid confusion with 'Elevator on floor 1 to floor 1'
    current.setStreetName(edge.getName());

    current.setRelativeDirection(RelativeDirection.ELEVATOR);

    steps.add(current);
  }

  private WalkStep createWalkStep(State forwardState, State backState) {
    Edge en = forwardState.getBackEdge();
    WalkStep step;
    step =
      new WalkStep(
        en.getName(),
        new WgsCoordinate(backState.getVertex().getLat(), backState.getVertex().getLon()),
        en.hasBogusName(),
        DirectionUtils.getFirstAngle(forwardState.getBackEdge().getGeometry()),
        forwardState.isBackWalkingBike(),
        forwardState.getBackEdge() instanceof AreaEdge
      );
    step.addElevation(
      encodeElevationProfile(
        forwardState.getBackEdge(),
        0,
        forwardState.getOptions().geoidElevation ? -ellipsoidToGeoidDifference : 0
      )
    );
    step.addStreetNotes(streetNotesService.getNotes(forwardState));
    return step;
  }
}
