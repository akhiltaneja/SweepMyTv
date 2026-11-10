package com.sweepmytv.launcher;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Ranks the regional TMDB cache locally. No viewing history or profile is sent to a server. */
final class Recommendations {
    static final String KEY = "sweep_recommendations";
    static final int LIMIT = 12;
    static boolean provider(String pkg) {
        // Candidates already passed the regional subscription-provider and installed-app checks in Trending.
        return pkg != null && !pkg.isEmpty() && !ContinueOptions.isYouTube(pkg);
    }
    private static final long HISTORY_MS = 90L * 24 * 3600_000;

    static final String NEW_KEY="sweep_new_releases";
    static boolean isRow(String key) {return KEY.equals(key)||NEW_KEY.equals(key);}
    private static String identity(Trending.Pick p) {return p.kind+":"+p.id;}
    private static Map<String,Trending.Pick> unique(List<Trending.Pick> catalog) {
        Map<String,Trending.Pick> out=new LinkedHashMap<>();
        for(Trending.Pick p:catalog)if(provider(p.pkg)&&p.image!=null&&p.image.isFile()
                && (Trending.TRENDING.equals(p.tab)||Trending.INDIA.equals(p.tab)||Trending.ENGLISH.equals(p.tab)||Trending.RELEASES.equals(p.tab))
                && ("movie".equals(p.kind)||"tv".equals(p.kind))&&p.title!=null&&!p.title.trim().isEmpty()) {
            Trending.Pick old=out.get(identity(p));
            if(old==null||p.weeklyTrending&&!old.weeklyTrending)out.put(identity(p),p);
        }
        return out;
    }
    static Channels.Channel build(List<Trending.Pick> catalog,List<Trending.Pick> history,long now) {
        Map<String,Trending.Pick> unique=unique(catalog);
        Map<String,Float> affinity=new HashMap<>(),genres=new HashMap<>();Set<String> watched=new HashSet<>();
        for(Trending.Pick h:history) {
            if(!provider(h.pkg)||h.at<=0||now-h.at>HISTORY_MS)continue;
            float weight=Math.max(.1f,1f-Math.max(0,now-h.at)/(float)HISTORY_MS);
            affinity.merge(h.pkg,weight,Math::max);String title=normalize(h.title);if(title.isEmpty())continue;
            watched.add(title);
            for(Trending.Pick p:unique.values())if(normalize(p.title).equals(title)&&!genre(p).isEmpty())genres.merge(genre(p),weight,Math::max);
        }
        List<Trending.Pick> candidates=new ArrayList<>();Map<String,Float> scores=new HashMap<>();int rank=0;
        for(Trending.Pick p:unique.values()) {
            float popular=1f-rank++/(float)Math.max(1,unique.size())*.5f;
            if(watched.contains(normalize(p.title)))continue;
            long age=releaseAgeDays(p,now);float freshness=age>=0&&age<=90?.8f*(1-age/90f):0;
            candidates.add(p);scores.put(identity(p),popular+(p.weeklyTrending?1.25f:0)+freshness
                    +affinity.getOrDefault(p.pkg,0f)*.75f+genres.getOrDefault(genre(p),0f)*.75f);
        }
        Channels.Channel row=select(KEY,"Recommended for you",candidates,scores);
        for(int i=0;i<row.items.size();i++) {
            Trending.Pick p=row.items.get(i);String reason=p.weeklyTrending?"Trending this week"
                    :releaseAgeDays(p,now)>=0&&releaseAgeDays(p,now)<=90?"Recent release"
                    :genres.containsKey(genre(p))?"More "+genre(p)
                    :affinity.containsKey(p.pkg)?"Based on recent "+p.service+" activity":"Popular on "+p.service;
            row.items.set(i,copy(p,reason,KEY));
        }
        return row;
    }
    static Channels.Channel newReleases(List<Trending.Pick> catalog,Channels.Channel recommended,long now) {
        Set<String> exclude=new HashSet<>();for(Trending.Pick p:recommended.items)exclude.add(identity(p));
        List<Trending.Pick> all=new ArrayList<>(),remaining=new ArrayList<>();Map<String,Float> scores=new HashMap<>();
        for(Trending.Pick p:unique(catalog).values()) {
            long age=releaseAgeDays(p,now);if(age<0||age>90)continue;
            all.add(p);if(!exclude.contains(identity(p)))remaining.add(p);
            scores.put(identity(p),1f-age/90f);
        }
        // Prefer distinct rows; allow shared entries only when needed to keep English represented or the row populated.
        if(remaining.isEmpty())remaining=all;
        else if(remaining.stream().noneMatch(p->"en".equals(p.lang)))
            for(Trending.Pick p:all)if("en".equals(p.lang))remaining.add(p);
        Channels.Channel row=select(NEW_KEY,"New releases",remaining,scores);
        for(int i=0;i<row.items.size();i++) {
            Trending.Pick p=row.items.get(i);String date=java.time.LocalDate.parse(p.releaseDate)
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu",Locale.ENGLISH));
            row.items.set(i,copy(p,"Premiered "+date,NEW_KEY));
        }
        return row;
    }
    static long releaseAgeDays(Trending.Pick p,long now) {
        try {
            long today=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay();
            return today-java.time.LocalDate.parse(p.releaseDate).toEpochDay();
        } catch(Exception ignored) {return Long.MIN_VALUE;}
    }
    private static Channels.Channel select(String key,String title,List<Trending.Pick> candidates,Map<String,Float> scores) {
        Channels.Channel row=new Channels.Channel();row.key=key;row.name=row.title=title;
        List<Trending.Pick> remaining=new ArrayList<>(candidates);Map<String,Integer> used=new HashMap<>();
        Set<String> providers=new HashSet<>();int englishAvailable=0;
        for(Trending.Pick p:remaining){providers.add(p.pkg);if("en".equals(p.lang))englishAvailable++;}
        int target=Math.min(6,englishAvailable),english=0;
        int quota=Math.max(4,(LIMIT+Math.max(1,providers.size())-1)/Math.max(1,providers.size()));
        while(row.items.size()<LIMIT&&!remaining.isEmpty()) {
            boolean needEnglish=english<target&&(row.items.size()%2==0||LIMIT-row.items.size()<=target-english);
            Trending.Pick best=null;float bestScore=-Float.MAX_VALUE;
            for(int pass=0;pass<2&&best==null;pass++)for(Trending.Pick p:remaining) {
                if(needEnglish&&!"en".equals(p.lang))continue;
                int count=used.getOrDefault(p.pkg,0);if(pass==0&&!needEnglish&&count>=quota)continue;
                float score=scores.getOrDefault(identity(p),0f)-count*.55f;
                if(score>bestScore){best=p;bestScore=score;}
            }
            if(best==null)break;
            remaining.remove(best);row.items.add(best);used.merge(best.pkg,1,Integer::sum);if("en".equals(best.lang))english++;
        }
        row.count=row.items.size();return row;
    }
    private static Trending.Pick copy(Trending.Pick p,String reason,String tab) {
        Trending.Pick out=new Trending.Pick();out.id=p.id;out.kind=p.kind;out.title=p.title;out.pkg=p.pkg;out.service=p.service;
        out.tab=tab;out.lang=p.lang;out.image=p.image;out.poster=p.poster;
        String metadata=p.meta==null?"":p.meta,suffix=" · "+p.service;
        if(metadata.endsWith(suffix))metadata=metadata.substring(0,metadata.length()-suffix.length());
        out.intentUri=p.intentUri;out.meta=reason+(metadata.isEmpty()?"":" · "+metadata);
        out.overview=p.overview;out.year=p.year;out.rating=p.rating;out.releaseDate=p.releaseDate;out.weeklyTrending=p.weeklyTrending;
        return out;
    }

    static String normalize(String title) {
        if (title == null) return "";
        String base = title.split("(?i)\\s+(?:season\\s+\\d+|s\\d+\\s*e\\d+|episode\\s+\\d+)", 2)[0];
        return Normalizer.normalize(base, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String genre(Trending.Pick p) {
        String[] parts = (p.meta == null ? "" : p.meta).split(" · ");
        // Only known TMDB genre labels, never a language or provider from a shorter metadata line.
        if (parts.length < 2) return "";
        String allowed = "|Action|Adventure|Animation|Comedy|Crime|Documentary|Drama|Family|Fantasy|History|Horror|Music|Mystery|Romance|Science Fiction|Sci-Fi|TV Movie|Thriller|War|Western|Kids|News|Reality|Soap|Talk|War & Politics|";
        return allowed.contains("|" + parts[1] + "|") ? parts[1] : "";
    }
}
