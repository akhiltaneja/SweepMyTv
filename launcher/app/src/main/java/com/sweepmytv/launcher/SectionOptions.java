package com.sweepmytv.launcher;

import android.content.Context;
import java.util.*;

/** Hides whole rows locally, without altering the provider's programs or app installation. */
final class SectionOptions {
    static final String HIDDEN = "hidden_sections";
    static boolean hidden(Context c,String key){return HomeOptions.prefs(c).getStringSet(HIDDEN,Collections.emptySet()).contains(key);}
    static void set(Context c,String key,boolean shown){Set<String> hidden=new HashSet<>(HomeOptions.prefs(c).getStringSet(HIDDEN,Collections.emptySet()));update(hidden,key,shown);HomeOptions.prefs(c).edit().putStringSet(HIDDEN,hidden).apply();}
    static void update(Set<String> hidden, String key, boolean shown) {
        if (shown) hidden.remove(key);
        else hidden.add(key);
    }
    static void filter(Context c,List<Channels.Channel> rows){rows.removeIf(row->hidden(c,row.key));}
}
