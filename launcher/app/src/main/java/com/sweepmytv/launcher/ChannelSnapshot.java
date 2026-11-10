package com.sweepmytv.launcher;

import java.io.File;
import java.util.*;

/** Prepared away from input dispatch; signatures identify rows whose existing views can be kept. */
final class ChannelSnapshot {
    final List<Channels.Channel> rows;
    final Map<String,String> signatures=new LinkedHashMap<>();
    final String signature;
    ChannelSnapshot(List<Channels.Channel> rows) {
        this.rows=rows;StringBuilder all=new StringBuilder();
        for(Channels.Channel row:rows){String key=signature(row);signatures.put(row.key,key);all.append(row.key).append(key);}
        signature=all.toString();
    }
    private static void add(StringBuilder out,Object value){String s=String.valueOf(value);out.append(s.length()).append(':').append(s);}
    private static void art(StringBuilder out,File file){add(out,file);if(file!=null){add(out,file.lastModified());add(out,file.length());}}
    static String signature(Channels.Channel row) {
        StringBuilder out=new StringBuilder();add(out,row.key);add(out,row.title);add(out,row.pkg);
        for(Trending.Pick p:row.items) {
            add(out,p.id);add(out,p.kind);add(out,p.pkg);add(out,p.title);add(out,p.meta);add(out,p.service);
            add(out,p.progress);add(out,p.savedResume);add(out,p.intentUri);add(out,p.overview);add(out,p.year);add(out,p.rating);art(out,p.image);art(out,p.poster);
            if(p.extra instanceof HomeInsights.Card){HomeInsights.Card c=(HomeInsights.Card)p.extra;add(out,c.kind);add(out,c.kicker);add(out,c.metric);add(out,c.body);add(out,c.foot);add(out,c.detail);add(out,c.target);add(out,c.color);for(HomeInsights.Story story:c.stories){add(out,story.title);add(out,story.url);add(out,story.source);add(out,story.published);}}
            if(p.extra instanceof FormulaOne.Card){
                FormulaOne.Card c=(FormulaOne.Card)p.extra;
                add(out,c.key);add(out,c.title);add(out,c.kicker);add(out,c.body);add(out,c.metric);add(out,c.foot);add(out,c.target);add(out,c.live);
                for(String line:c.lines)add(out,line);
                for(FormulaOne.Line line:c.table){add(out,line.rank);add(out,line.name);add(out,line.value);add(out,line.team);add(out,line.portrait);add(out,line.logo);add(out,line.color);}
            }
        }
        return out.toString();
    }
}
