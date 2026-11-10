package com.sweepmytv.launcher;

import org.json.JSONObject;
import java.time.*;

/** Only approximate city coordinates; does not retain an IP address or access Android's location APIs. */
final class ApproximateCity {
    final String name,zone,country,region;
    final double lat,lon;
    private ApproximateCity(String name,String zone,double lat,double lon,String country,String region){this.name=name;this.zone=zone;this.lat=lat;this.lon=lon;this.country=country;this.region=region;}
    static ApproximateCity parse(JSONObject response,ZoneId deviceZone,long now) {
        if(response.optBoolean("is_bogon",false)||response.has("error"))return null;
        JSONObject nested=response.optJSONObject("location");if(nested!=null)response=nested;
        String city=response.optString("city",""),zone=response.optString("timezone","");
        double lat=response.optDouble("lat",response.optDouble("latitude",Double.NaN)),lon=response.optDouble("lon",response.optDouble("longitude",Double.NaN));
        if(city.isEmpty()||city.equals("null")||!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return null;
        try {
            // A large timezone conflict is a useful VPN/ISP-routing cue. Keep the clock fallback rather than guessing a remote city.
            int remote=ZoneId.of(zone).getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds();
            int local=deviceZone.getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds();
            if(Math.abs(remote-local)>3600)return null;
        }catch(Exception e){return null;}
        return new ApproximateCity(city,zone,Math.round(lat*100)/100.0,Math.round(lon*100)/100.0,response.optString("country_code",""),response.optString("state",response.optString("region","")));
    }
}
