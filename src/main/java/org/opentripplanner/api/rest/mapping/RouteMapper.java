package org.opentripplanner.api.rest.mapping;

import org.opentripplanner.api.rest.model.ApiRoute;
import org.opentripplanner.api.rest.model.ApiRouteShort;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Branding;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class RouteMapper {

    public static List<ApiRoute> mapToApi(Collection<Route> domain) {
        if(domain == null) { return null; }
        return domain.stream().map(RouteMapper::mapToApi).collect(Collectors.toList());
    }

    public static ApiRoute mapToApi(Route domain) {
        if(domain == null) { return null; }

        ApiRoute api = new ApiRoute();
        api.id = FeedScopedIdMapper.mapToApi(domain.getId());
        api.agency = AgencyMapper.mapToApi(domain.getAgency());
        api.shortName = domain.getShortName();
        api.longName = domain.getLongName();
        api.mode = TraverseModeMapper.mapToApi(domain.getMode());
        api.type = domain.getGtfsType() != null
            ? domain.getGtfsType()
            : RouteTypeMapper.mapToApi(domain.getMode());
        api.desc = domain.getDesc();
        api.url = domain.getUrl();
        api.color = domain.getColor();
        api.textColor = domain.getTextColor();
        api.bikesAllowed = BikeAccessMapper.mapToApi(domain.getBikesAllowed());
        api.sortOrder = domain.isSortOrderSet() ? domain.getSortOrder() : null;

        Branding branding = domain.getBranding();
        if (branding != null) {
            api.brandingUrl = branding.getUrl();
        }

        return api;
    }

    public static List<ApiRouteShort> mapToApiShort(Collection<Route> domain) {
        if(domain == null) { return null; }
        return domain.stream().map(RouteMapper::mapToApiShort).collect(Collectors.toList());
    }

    public static ApiRouteShort mapToApiShort(Route domain) {
        if(domain == null) { return null; }

        ApiRouteShort api = new ApiRouteShort();
        api.id = FeedScopedIdMapper.mapToApi(domain.getId());
        api.shortName = domain.getShortName();
        api.longName = domain.getLongName();
        api.mode = TraverseModeMapper.mapToApi(domain.getMode());
        api.color = domain.getColor();
        api.agencyName = domain.getAgency().getName();

        return api;
    }
}