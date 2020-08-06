package org.opentripplanner.graph_builder.module;

import com.beust.jcommander.internal.Lists;
import com.beust.jcommander.internal.Sets;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.opentripplanner.common.MinMap;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.TripPattern;
import org.opentripplanner.routing.algorithm.astar.AStar;
import org.opentripplanner.routing.algorithm.astar.strategies.TrivialRemainingWeightHeuristic;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.graph_finder.DirectGraphFinder;
import org.opentripplanner.routing.graph_finder.StopAtDistance;
import org.opentripplanner.routing.spt.DominanceFunction;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.vertextype.TransitStopVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * These library functions are used by the streetless and streetful stop linkers, and in profile transfer generation.
 * TODO OTP2 Fold these into org.opentripplanner.routing.graph_finder.StreetGraphFinder
 *           These are not library functions, this is instantiated as an object. Define lifecycle of the object (reuse?).
 *           Because AStar instances should only be used once, NearbyStopFinder should only be used once.
 * Ideally they could also be used in long distance mode and profile routing for the street segments.
 * For each stop, it finds the closest stops on all other patterns. This reduces the number of transfer edges
 * significantly compared to simple radius-constrained all-to-all stop linkage.
 */
public class NearbyStopFinder {

    private static Logger LOG = LoggerFactory.getLogger(NearbyStopFinder.class);
    private static GeometryFactory geometryFactory = GeometryUtils.getGeometryFactory();

    public  final boolean useStreets;
    private Graph graph;
    private double radiusMeters;

    /* Fields used when finding stops via the street network. */
    private AStar astar;

    private DirectGraphFinder directGraphFinder;

    /**
     * Construct a NearbyStopFinder for the given graph and search radius, choosing whether to search via the street
     * network or straight line distance based on the presence of OSM street data in the graph.
     */
    public NearbyStopFinder(Graph graph, double radiusMeters) {
        this (graph, radiusMeters, graph.hasStreets);
    }

    /**
     * Construct a NearbyStopFinder for the given graph and search radius.
     * @param useStreets if true, search via the street network instead of using straight-line distance.
     */
    public NearbyStopFinder(Graph graph, double radiusMeters, boolean useStreets) {
        this.graph = graph;
        this.useStreets = useStreets;
        this.radiusMeters = radiusMeters;
        if (useStreets) {
            astar = new AStar();
            // We need to accommodate straight line distance (in meters) but when streets are present we use an
            // earliest arrival search, which optimizes on time. Ideally we'd specify in meters,
            // but we don't have much of a choice here. Use the default walking speed to convert.
        } else {
            this.directGraphFinder = new DirectGraphFinder(graph);
        }
    }

    /**
     * Find all unique nearby stops that are the closest stop on some trip pattern.
     * Note that the result will include the origin vertex if it is an instance of StopVertex.
     * This is intentional: we don't want to return the next stop down the line for trip patterns that pass through the
     * origin vertex.
     */
    public Set<StopAtDistance> findNearbyStopsConsideringPatterns (Vertex vertex) {

        /* Track the closest stop on each pattern passing nearby. */
        MinMap<TripPattern, StopAtDistance> closestStopForPattern = new MinMap<TripPattern, StopAtDistance>();

        /* Iterate over nearby stops via the street network or using straight-line distance, depending on the graph. */
        for (StopAtDistance stopAtDistance : findNearbyStops(vertex)) {
            Stop ts1 = stopAtDistance.stop;
            /* Consider this destination stop as a candidate for every trip pattern passing through it. */
            for (TripPattern pattern : graph.index.getPatternsForStop(ts1)) {
                closestStopForPattern.putMin(pattern, stopAtDistance);
            }
        }

        /* Make a transfer from the origin stop to each destination stop that was the closest stop on any pattern. */
        Set<StopAtDistance> uniqueStops = Sets.newHashSet();
        uniqueStops.addAll(closestStopForPattern.values());
        return uniqueStops;

    }


    /**
     * Return all stops within a certain radius of the given vertex, using network distance along streets.
     * Use the correct method depending on whether the graph has street data or not.
     * If the origin vertex is a StopVertex, the result will include it; this characteristic is essential for
     * associating the correct stop with each trip pattern in the vicinity.
     */
    public List<StopAtDistance> findNearbyStops (Vertex vertex) {
        if (useStreets) {
            return findNearbyStopsViaStreets(vertex);
        }
        Coordinate c0 = vertex.getCoordinate();
        return directGraphFinder.findClosestStops(c0.y, c0.x, radiusMeters);
    }


    /**
     * Return all stops within a certain radius of the given vertex, using network distance along streets.
     * If the origin vertex is a StopVertex, the result will include it.
     *
     * @param originVertices the origin point of the street search
     * @param reverseDirection if true the paths returned instead originate at the nearby stops and have the
     *                         originVertex as the destination
     * @param removeTempEdges after creating a new routing request and routing context, remove all the temporary
     *                        edges that are part of that context. NOTE: this will remove _all_ temporary edges
     *                        coming out of the origin and destination vertices, including those in any other
     *                        RoutingContext referencing them, making routing from/to them totally impossible.
     *                        This is a stopgap solution until we rethink the lifecycle of RoutingContext.
     */
    public List<StopAtDistance> findNearbyStopsViaStreets (
            Set<Vertex> originVertices,
            boolean reverseDirection,
            boolean removeTempEdges,
            RoutingRequest routingRequest
    ) {
        routingRequest.arriveBy = reverseDirection;
        if (!reverseDirection) {
            routingRequest.setRoutingContext(graph, originVertices, null);
        } else {
            routingRequest.setRoutingContext(graph, null, originVertices);
        }
        int walkTime = (int) (radiusMeters / new RoutingRequest().walkSpeed);
        routingRequest.worstTime = routingRequest.dateTime + (reverseDirection ? -walkTime : walkTime);
        routingRequest.disableRemainingWeightHeuristic = true;
        routingRequest.rctx.remainingWeightHeuristic = new TrivialRemainingWeightHeuristic();
        routingRequest.dominanceFunction = new DominanceFunction.MinimumWeight();
        ShortestPathTree spt = astar.getShortestPathTree(routingRequest);

        List<StopAtDistance> stopsFound = Lists.newArrayList();
        if (spt != null) {
            // TODO use GenericAStar and a traverseVisitor? Add an earliestArrival switch to genericAStar?
            for (State state : spt.getAllStates()) {
                Vertex targetVertex = state.getVertex();
                if (originVertices.contains(targetVertex)) continue;
                if (targetVertex instanceof TransitStopVertex && state.isFinal()) {
                    stopsFound.add(StopAtDistance.stopAtDistanceForState(state));
                }
            }
        }
        /* Add the origin vertices if needed. The SPT does not include the initial state. FIXME shouldn't it? */
        for (Vertex vertex : originVertices) {
            if (vertex instanceof TransitStopVertex) {
                stopsFound.add(
                    new StopAtDistance(
                        (TransitStopVertex) vertex,
                        0,
                        Collections.emptyList(),
                        null,
                        new State(vertex, routingRequest)
                    ));
            }
        }
        if (removeTempEdges) {
            routingRequest.cleanup();
        }
        return stopsFound;

    }

    public List<StopAtDistance> findNearbyStopsViaStreets (
        Set<Vertex> originVertices,
        boolean reverseDirection,
        boolean removeTempEdges
    ) {
        RoutingRequest routingRequest = new RoutingRequest(TraverseMode.WALK);
        return findNearbyStopsViaStreets(
            originVertices,
            reverseDirection,
            removeTempEdges,
            routingRequest
        );
    }

    public List<StopAtDistance> findNearbyStopsViaStreets (Vertex originVertex) {
        return findNearbyStopsViaStreets(
                Collections.singleton(originVertex),
                false,
                true
        );
    }

}
