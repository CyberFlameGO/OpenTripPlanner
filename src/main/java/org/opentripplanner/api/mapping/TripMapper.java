package org.opentripplanner.api.mapping;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.opentripplanner.api.model.ApiTrip;
import org.opentripplanner.api.model.ApiTripShort;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.timetable.Trip;

public class TripMapper {

  public static ApiTrip mapToApi(Trip obj) {
    if (obj == null) {
      return null;
    }

    ApiTrip api = new ApiTrip();
    api.id = FeedScopedIdMapper.mapToApi(obj.getId());
    api.routeId = FeedScopedIdMapper.mapIdToApi(obj.getRoute());
    api.serviceId = FeedScopedIdMapper.mapToApi(obj.getServiceId());
    api.tripShortName = obj.getShortName();
    api.tripHeadsign = obj.getHeadsign();
    api.routeShortName = obj.getRoute().getShortName();
    api.directionId = obj.getGtfsDirectionIdAsString(null);
    api.blockId = obj.getGtfsBlockId();
    api.shapeId = FeedScopedIdMapper.mapToApi(obj.getShapeId());
    api.wheelchairAccessible = obj.getWheelchairBoarding().gtfsCode;
    api.bikesAllowed = BikeAccessMapper.mapToApi(obj.getBikesAllowed());
    api.fareId = obj.getGtfsFareId();

    return api;
  }

  public static ApiTripShort mapToApiShort(Trip domain) {
    if (domain == null) {
      return null;
    }

    ApiTripShort api = new ApiTripShort();
    api.id = FeedScopedIdMapper.mapToApi(domain.getId());
    api.tripHeadsign = domain.getHeadsign();
    api.serviceId = FeedScopedIdMapper.mapToApi(domain.getServiceId());
    FeedScopedId shape = domain.getShapeId();

    // TODO OTP2 - All ids should be fully qualified including feed scope id.
    api.shapeId = shape == null ? null : shape.getId();
    api.direction = domain.getDirection().gtfsCode;

    return api;
  }

  public static List<ApiTripShort> mapToApiShort(Stream<Trip> domain) {
    if (domain == null) {
      return null;
    }
    return domain.map(TripMapper::mapToApiShort).collect(Collectors.toList());
  }
}
