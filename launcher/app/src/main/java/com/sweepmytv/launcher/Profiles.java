package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** Local launcher identities. Streaming app sign-ins and Continue Watching remain shared on this TV. */
final class Profiles {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("profiles",0);}
    static synchronized String active(Context c){
        String id=prefs(c).getString("active","");if(!id.isEmpty())return id;
        id=UUID.randomUUID().toString();
        prefs(c).edit().putString("active",id).putString("ids",id).putString("name."+id,HomeOptions.name(c))
            .putString("languages."+id,String.join(",",ContentLanguages.selected(c))).putInt("avatar."+id,unusedRandomAvatar(c)).commit();
        return id;
    }
    static List<String> ids(Context c){active(c);return new ArrayList<>(Arrays.asList(prefs(c).getString("ids","").split(",")));}
    /** Automatic assignments and rerolls avoid every avatar currently used on this TV. */
    private static int unusedRandomAvatar(Context c){
        SharedPreferences saved=prefs(c);Set<Integer> used=new HashSet<>();
        // Read raw IDs so first-profile creation never recursively calls active().
        for(String id:saved.getString("ids","").split(","))if(!id.isEmpty()){
            int avatar=saved.getInt("avatar."+id,-1);if(avatar>=0&&avatar<Avatars.COUNT)used.add(avatar);
        }
        List<Integer> available=new ArrayList<>();for(int i=0;i<Avatars.COUNT;i++)if(!used.contains(i))available.add(i);
        // There are 36 avatars and at most 6 profiles; this only guards future imported data.
        if(available.isEmpty())throw new IllegalStateException("No unused avatar available");
        return available.get(new java.security.SecureRandom().nextInt(available.size()));
    }
    static void randomizeAvatar(Context c){active(c);avatar(c,unusedRandomAvatar(c));}
    static String name(Context c,String id){return prefs(c).getString("name."+id,"");}
    static int avatar(Context c){return prefs(c).getInt("avatar."+active(c),0);}
    static void avatar(Context c,int index){if(index < -1||index>=Avatars.COUNT)throw new IllegalArgumentException();prefs(c).edit().putInt("avatar."+active(c),index).apply();Avatars.clear();}
    static void remember(Context c){String id=active(c);prefs(c).edit().putString("name."+id,HomeOptions.name(c)).putString("languages."+id,String.join(",",ContentLanguages.selected(c))).commit();}
    static synchronized String add(Context c,String name){
        remember(c);String id=UUID.randomUUID().toString();
        prefs(c).edit().putString("ids",String.join(",",ids(c))+","+id).putString("name."+id,HomeOptions.cleanName(name))
            .putString("languages."+id,String.join(",",ContentLanguages.selected(c))).putInt("avatar."+id,unusedRandomAvatar(c)).commit();return id;
    }
    static boolean switchTo(Context c,String id){
        if(!ids(c).contains(id))return false;remember(c);
        if(!Keys.write(c,HomeOptions.NAME,name(c,id)))return false;
        c.getSharedPreferences("trending",0).edit().putString("languages",prefs(c).getString("languages."+id,"")).apply();
        prefs(c).edit().putString("active",id).commit();Avatars.clear();return true;
    }
    static java.io.File photo(Context c,String id){if(!ids(c).contains(id))throw new IllegalArgumentException();return new java.io.File(c.getFilesDir(),"avatar-"+id+".jpg");}
    static void photoSelected(Context c,String id){if(!ids(c).contains(id))return;prefs(c).edit().putInt("avatar."+id,-1).apply();Avatars.clear();}
}
