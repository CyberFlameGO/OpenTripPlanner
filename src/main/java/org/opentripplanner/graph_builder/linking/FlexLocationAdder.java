package org.opentripplanner.graph_builder.linking;

import java.util.HashSet;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Point;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.model.FlexStopLocation;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.vertextype.SplitterVertex;
import org.opentripplanner.transit.service.StopModel;

class FlexLocationAdder {

  static void addFlexLocations(StreetEdge edge, SplitterVertex v0, StopModel stopModel) {
    if (
      stopModel.getStopModelIndex() != null &&
      edge.getPermission().allows(StreetTraversalPermission.PEDESTRIAN_AND_CAR)
    ) {
      Point p = GeometryUtils.getGeometryFactory().createPoint(v0.getCoordinate());
      Envelope env = p.getEnvelopeInternal();
      for (FlexStopLocation location : stopModel.getStopModelIndex().locationIndex.query(env)) {
        if (!location.getGeometry().disjoint(p)) {
          if (v0.flexStopLocations == null) {
            v0.flexStopLocations = new HashSet<>();
          }
          v0.flexStopLocations.add(location);
        }
      }
    }
  }
}
