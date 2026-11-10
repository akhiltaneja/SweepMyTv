package com.sweepmytv.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;

/** Immutable forecast input for the sky. Dates and solar events retain the forecast's own timezone. */
final class SkyWeather {
    static final class Sample {
        final long sunrise,sunset,fetchedAt;
        final double clouds,rain;
        final int code;
        final boolean solarKnown,weatherKnown;
        final String city;
        Sample(long rise,long set,double clouds,double rain,int code,boolean solar,boolean weather,String city,long fetchedAt) {
            sunrise=rise;sunset=set;this.clouds=clamp(clouds,0,100);this.rain=Math.max(0,rain);this.code=code;
            solarKnown=solar;weatherKnown=weather;this.city=city;this.fetchedAt=fetchedAt;
        }
    }
    private final JSONObject forecast;
    private final ZoneId zone;
    private final String city;
    private final long fetchedAt;
    SkyWeather(JSONObject forecast,String city,long fetchedAt) {
        this.forecast=forecast;this.city=city;this.fetchedAt=fetchedAt;
        ZoneId parsed;try{parsed=ZoneId.of(forecast.optString("timezone"));}catch(Exception e){parsed=ZoneId.systemDefault();}zone=parsed;
    }
    static Sample fallback(long now) {
        LocalDate date=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
        return new Sample(date.atTime(6,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                date.atTime(18,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),0,0,0,false,false,"Local clock",0);
    }
    Sample at(long now) {
        Sample fallback=fallback(now);long rise=fallback.sunrise,set=fallback.sunset;boolean solar=false;
        String today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();JSONObject daily=forecast.optJSONObject("daily");
        if(daily!=null) {
            JSONArray dates=daily.optJSONArray("time"),rises=daily.optJSONArray("sunrise"),sets=daily.optJSONArray("sunset");
            for(int i=0;dates!=null&&i<dates.length();i++)if(today.equals(dates.optString(i))) {
                long r=parse(rises==null?"":rises.optString(i)),s=parse(sets==null?"":sets.optString(i));
                if(r>0&&s-r>=3*3600_000L&&s-r<=22*3600_000L){rise=r;set=s;solar=true;}break;
            }
        }
        double clouds=0,rain=0;int code=0;boolean weather=false;
        if(now>=fetchedAt-3600_000L&&now-fetchedAt<=48*3600_000L) {
            JSONObject hourly=forecast.optJSONObject("hourly");JSONArray times=hourly==null?null:hourly.optJSONArray("time");
            for(int i=0;times!=null&&i<times.length();i++) {
                long start=parse(times.optString(i)),end=i+1<times.length()?parse(times.optString(i+1)):start+3600_000L;
                if(now>=start&&now<end&&end>start) {
                    double f=(now-start)/(double)(end-start);
                    clouds=mix(value(hourly,"cloud_cover",i,cloudForCode((int)value(hourly,"weather_code",i,0))),
                            value(hourly,"cloud_cover",i+1,cloudForCode((int)value(hourly,"weather_code",i,0))),f);
                    rain=mix(value(hourly,"precipitation",i,0),value(hourly,"precipitation",i+1,0),f);
                    code=(int)value(hourly,"weather_code",i,0);weather=true;break;
                }
            }
            JSONObject current=forecast.optJSONObject("current");long observed=current==null?0:parse(current.optString("time"));
            if(current!=null&&observed>0&&Math.abs(now-observed)<=45*60_000L) {
                double weight=1-Math.abs(now-observed)/(double)(45*60_000L);
                int currentCode=current.optInt("weather_code",code);
                clouds=mix(clouds,current.optDouble("cloud_cover",cloudForCode(currentCode)),weather?weight:1);
                rain=mix(rain,current.optDouble("precipitation",0),weather?weight:1);code=currentCode;weather=true;
            }
        }
        return new Sample(rise,set,clouds,rain,code,solar,weather,city,fetchedAt);
    }
    private long parse(String text) {try{return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli();}catch(Exception e){return 0;}}
    private static double value(JSONObject o,String name,int index,double fallback) {
        JSONArray a=o.optJSONArray(name);return a==null||index>=a.length()||a.isNull(index)?fallback:a.optDouble(index,fallback);
    }
    private static double cloudForCode(int code) {return code==0?0:code==1?20:code==2?55:code==3?100:85;}
    static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,Double.isFinite(value)?value:min));}
    private static double mix(double a,double b,double f){return a+(b-a)*f;}
}
