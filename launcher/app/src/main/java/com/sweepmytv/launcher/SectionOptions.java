package com.sweepmytv.launcher;

import android.content.Context;
import java.util.*;

/** Hides whole rows locally, without altering the provider's programs or app installation. */
final class SectionOptions {
    static boolean hidden(Context c,String key){return HomeOptions.prefs(c).getStringSet("hidden_sections",Collections.emptySet()).contains(key);}
    static void set(Context c,String key,boolean shown){Set<String> hidden=new HashSet<>(HomeOptions.prefs(c).getStringSet("hidden_sections",Collections.emptySet()));if(shown)hidden.remove(key);else hidden.add(key);HomeOptions.prefs(c).edit().putStringSet("hidden_sections",hidden).apply();}
    static void filter(Context c,List<Channels.Channel> rows){rows.removeIf(row->hidden(c,row.key));}
}
