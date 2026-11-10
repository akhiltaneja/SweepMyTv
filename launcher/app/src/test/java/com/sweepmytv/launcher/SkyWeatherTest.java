package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class SkyWeatherTest {
    private static long at(String time){return LocalDateTime.parse(time).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();}
    private JSONObject forecast() throws Exception {
        return new JSONObject("{\"timezone\":\"Asia/Kolkata\",\"daily\":{\"time\":[\"2026-11-02\",\"2026-11-03\"],\"sunrise\":[\"2026-11-02T06:35\",\"2026-11-03T06:36\"],\"sunset\":[\"2026-11-02T17:35\",\"2026-11-03T17:34\"]},\"hourly\":{\"time\":[\"2026-11-02T14:00\",\"2026-11-02T15:00\"],\"cloud_cover\":[20,80],\"precipitation\":[0,2],\"weather_code\":[2,61]}}");
    }
    @Test public void delhiSolarTimesUseForecastTimezoneAndCloudsInterpolate() throws Exception {
        long now=at("2026-11-02T14:30");SkyWeather.Sample sample=new SkyWeather(forecast(),"Delhi",now).at(now);
        assertEquals(at("2026-11-02T06:35"),sample.sunrise);assertEquals(at("2026-11-02T17:35"),sample.sunset);
        assertTrue(sample.solarKnown);assertTrue(sample.weatherKnown);assertEquals(50,sample.clouds,.01);assertEquals(1,sample.rain,.01);
        SkyWeather.Sample tomorrow=new SkyWeather(forecast(),"Delhi",now).at(at("2026-11-03T00:01"));
        assertEquals(at("2026-11-03T06:36"),tomorrow.sunrise);assertFalse(tomorrow.weatherKnown);
    }
    @Test public void expiredAndMissingWeatherUseHonestFallbacks() throws Exception {
        long now=at("2026-11-02T14:30");SkyWeather.Sample old=new SkyWeather(forecast(),"Delhi",now-49*3600_000L).at(now);
        assertTrue(old.solarKnown);assertFalse(old.weatherKnown);assertEquals(0,old.clouds,0);
        SkyWeather.Sample missing=new SkyWeather(new JSONObject(),"",now).at(now);
        assertFalse(missing.solarKnown);assertFalse(missing.weatherKnown);
    }
    @Test public void actualSolarTimesAndWeatherChangeTheSkyWithoutChangingTvClock() {
        long rise=at("2026-11-02T06:35"),set=at("2026-11-02T17:35"),now=at("2026-11-02T14:00");
        TimeOfDay.Palette clear=TimeOfDay.palette(now,new SkyWeather.Sample(rise,set,0,0,0,true,true,"Delhi",now));
        TimeOfDay.Palette wet=TimeOfDay.palette(now,new SkyWeather.Sample(rise,set,100,8,95,true,true,"Delhi",now));
        TimeOfDay.Palette shifted=TimeOfDay.palette(now,new SkyWeather.Sample(rise+3600_000L,set+3600_000L,0,0,0,true,true,"Delhi",now));
        assertNotEquals(clear.top,wet.top);assertNotEquals(clear.bottom,shifted.bottom);
        java.util.Set<String> hours=new java.util.HashSet<>();
        for(int hour=0;hour<24;hour++){TimeOfDay.Palette p=TimeOfDay.palette(hour*60);hours.add(p.top+":"+p.mid+":"+p.bottom);}
        assertEquals(24,hours.size());
        assertTrue(TimeOfDay.luminance(TimeOfDay.palette(15*60).bottom)>TimeOfDay.luminance(TimeOfDay.palette(12*60).bottom));
        for(int minute=0;minute<1440;minute++) {
            TimeOfDay.Palette p=TimeOfDay.palette(at("2026-11-02T00:00")+minute*60_000L,new SkyWeather.Sample(rise,set,90,5,61,true,true,"Delhi",now));
            for(int bg:new int[]{p.top,p.mid,p.bottom}){assertTrue(TimeOfDay.contrast(p.text,bg)>=4.5);assertTrue(TimeOfDay.contrast(p.muted,bg)>=4.5);}
        }
    }
    @Test public void ipCityIsApproximateAndRejectsTimezoneConflictsOrInvalidData() throws Exception {
        long now=at("2026-11-02T14:00");ZoneId zone=ZoneId.of("Asia/Kolkata");
        JSONObject response=new JSONObject().put("city","Delhi").put("timezone","Asia/Kolkata").put("lat",28.6139).put("lon",77.2090);
        ApproximateCity city=ApproximateCity.parse(response,zone,now);assertNotNull(city);assertEquals(28.61,city.lat,0);assertEquals(77.21,city.lon,0);
        assertNull(ApproximateCity.parse(new JSONObject(),zone,now));
        response.put("timezone","America/New_York");assertNull(ApproximateCity.parse(response,zone,now));
        response.put("timezone","Asia/Kolkata").put("lat",91);assertNull(ApproximateCity.parse(response,zone,now));
    }
    @Test public void manualCityOverridesAutoAndOldCityWeatherIsNeverReusedForANewCity() throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        long now=at("2026-11-02T14:30");
        app.getSharedPreferences("weather",0).edit().putString("sky_forecast",forecast().toString()).putLong("sky_at",now)
                .putString("sky_area","Delhi, India").putString("weather_area","Delhi, India").putString("city","Delhi").commit();
        java.io.File file=new java.io.File(app.getExternalFilesDir(null),"location.txt");
        java.nio.file.Files.write(file.toPath(),"Delhi, India".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Weather weather=new Weather(app);assertTrue(weather.automatic());assertEquals("Delhi, India",weather.locationLabel());
        assertTrue(weather.sky(now).solarKnown);
        java.nio.file.Files.write(file.toPath(),"Mumbai, India".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(weather.sky(now).solarKnown);assertNull(weather.cached());
        assertTrue(file.delete());weather.automatic(false);assertEquals("Off",weather.locationLabel());assertFalse(weather.sky(now).solarKnown);
    }
}
