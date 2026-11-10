package com.sweepmytv.launcher;

import android.content.Context;
import java.util.*;

/** Original title language, independent of the TV region and of dubbed audio availability. */
final class ContentLanguages {
    static final List<String> AVAILABLE=Arrays.asList("en","hi","ta","te","ml","kn","bn","mr","pa","gu","ur","es","fr","de","it","pt","ja","ko","zh","cn","ar","tr","th","id","ru");
    static Set<String> selected(Context c){
        Set<String> out=new LinkedHashSet<>();
        for(String code:c.getSharedPreferences("trending",0).getString("languages","").split(","))if(!code.isEmpty())out.add(code);
        return out;
    }
    static boolean allows(Set<String> chosen,String language){return chosen.isEmpty()||chosen.contains(language==null?"":language);}
    static List<Trending.Pick> filter(List<Trending.Pick> input,Set<String> chosen){
        List<Trending.Pick> out=new ArrayList<>();for(Trending.Pick p:input)if(allows(chosen,p.lang))out.add(p);return out;
    }
    /** Never mutate providers' saved rows: changing the preference can restore them. History stays unfiltered. */
    static List<Channels.Channel> channels(List<Channels.Channel> input,Set<String> chosen){
        if(chosen.isEmpty())return new ArrayList<>(input);
        List<Channels.Channel> out=new ArrayList<>();
        for(Channels.Channel row:input){
            if(Channels.WATCH_NEXT.equals(row.key)){out.add(row);continue;}
            Channels.Channel copy=new Channels.Channel();copy.key=row.key;copy.pkg=row.pkg;copy.name=row.name;copy.title=row.title;copy.id=row.id;copy.shown=row.shown;
            copy.items.addAll(filter(row.items,chosen));copy.count=copy.items.size();if(!copy.items.isEmpty())out.add(copy);
        }
        return out;
    }
}
