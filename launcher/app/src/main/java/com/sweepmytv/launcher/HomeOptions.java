package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;

/** Personalisation stays on the TV; it never queries Google account identities. */
final class HomeOptions {
    static final String NAME="profile_name.txt";
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("home",0);}
    static String name(Context c){String name=Keys.read(c,NAME);return cleanName(name);}
    static String cleanName(String name){if(name==null)return "";String s=name.replaceAll("[\\p{Cntrl}]"," ").trim().replaceAll("\\s+"," ");return s.length()>24?s.substring(0,24):s;}
    static String greeting(int hour,String name){String greeting=hour<5?"Good evening":hour<12?"Good morning":hour<17?"Good afternoon":"Good evening";String n=cleanName(name);return greeting+(n.isEmpty()?"":", "+n);}
    static String header(Context c,long now,String weather){
        Calendar clock=Calendar.getInstance();clock.setTimeInMillis(now);SharedPreferences p=prefs(c);
        String hello=p.getBoolean("header_greeting",true)?greeting(clock.get(Calendar.HOUR_OF_DAY),name(c)):"";
        String forecast=p.getBoolean("header_weather",true)&&weather!=null?weather:"";
        return hello.isEmpty()?forecast:forecast.isEmpty()?hello:hello+"  ·  "+forecast;
    }
    static boolean stat(Context c,String key){return prefs(c).getBoolean("widget_"+key,"ram".equals(key)||"storage".equals(key)||"codex".equals(key)&&CodexUsage.read(c)!=null);}
    static void stat(Context c,String key,boolean shown){prefs(c).edit().putBoolean("widget_"+key,shown).apply();}
}
