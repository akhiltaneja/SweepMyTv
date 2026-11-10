package com.sweepmytv.launcher;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Read-only information embedded in this exact APK, never a remote dependency feed. */
final class ReleaseInfo {
    private ReleaseInfo() {}
    static final class Component {
        final String name,detail;
        Component(String name,String detail){this.name=name;this.detail=detail;}
    }
    private static JSONObject read(Context c) {
        try(InputStream in=c.getAssets().open("sweep-release.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>64*1024)return new JSONObject();out.write(b,0,n);}
            JSONObject release=new JSONObject(out.toString("UTF-8"));
            if(!SelfUpdate.installed(c).equals(release.optString("version")))return new JSONObject();
            return release;
        }catch(Exception ignored){return new JSONObject();}
    }
    static List<String> notes(Context c) {
        List<String> out=new ArrayList<>();JSONArray notes=read(c).optJSONArray("notes");
        for(int i=0;notes!=null&&i<notes.length();i++)out.add(notes.optString(i));
        if(out.isEmpty())out.add("Release notes are unavailable for this build.");return out;
    }
    static List<Component> components(Context c) {
        List<Component> out=new ArrayList<>();JSONObject release=read(c);JSONObject upstream=release.optJSONObject("verified_upstream");
        if(upstream!=null)out.add(new Component("CleanYourTV "+upstream.optString("release"),"Shared source reviewed · "+upstream.optString("commit").substring(0,Math.min(12,upstream.optString("commit").length()))+" · "+upstream.optString("review_scope")));
        JSONArray components=release.optJSONArray("components");
        for(int i=0;components!=null&&i<components.length();i++) {
            JSONObject component=components.optJSONObject(i);if(component!=null)out.add(new Component(component.optString("name"),component.optString("detail")));
        }
        return out;
    }
}
