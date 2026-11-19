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
import java.util.function.Function;
import java.util.function.Predicate;

/** Ranks the regional catalog locally. No viewing history or profile is sent to a server. */
final class Recommendations {
    static final String KEY = "sweep_recommendations";
    static final String TITLE = "Our Recommendations";
    static final String EXPLORE = "sweep_explore";
    static final int LIMIT = 12;
    static final long WINDOW_MS = 6 * 3600_000L;

    static long window(long time) {
        return Math.floorDiv(time + java.util.TimeZone.getDefault().getOffset(time), WINDOW_MS);
    }
    static boolean provider(String pkg) {
        // Candidates already passed the regional subscription-provider and installed-app checks in Trending.
        return pkg != null && !pkg.isEmpty() && !ContinueOptions.isYouTube(pkg);
    }
    private static final long HISTORY_MS = 90L * 24 * 3600_000;

    static final String NEW_KEY="sweep_new_releases";
    private static final String APP_PREFIX = "sweep_app_recommendations|";
    static final int APP_LIMIT = 12;

    static String appKey(String pkg) { return APP_PREFIX + pkg; }
    static boolean isAppRow(String key) { return key != null && key.startsWith(APP_PREFIX); }
    static boolean isRow(String key) { return KEY.equals(key) || NEW_KEY.equals(key) || EXPLORE.equals(key)
            || RottenTomatoesPicks.KEY.equals(key) || isAppRow(key); }
    static boolean appProvider(String pkg) { return provider(pkg) && !Cricket.FANCODE.equals(pkg); }

    static List<Channels.Channel> appRows(List<Trending.Pick> catalog, Set<String> visibleApps) {
        return appRows(catalog, visibleApps, apps -> List.of());
    }

    static List<Channels.Channel> appRows(List<Trending.Pick> catalog, Set<String> visibleApps,
                                          Function<Set<String>, List<Trending.Pick>> providerFallback) {
        Map<String, Channels.Channel> rows = new LinkedHashMap<>();
        addAppPicks(catalog, visibleApps, rows, p -> isAppRow(p.tab) || managedChart(p, Trending.TRENDING));
        addAppPicks(catalog, visibleApps, rows, p -> managedChart(p, Trending.INDIA));
        Set<String> shortApps = shortAppRows(rows.values(), visibleApps);
        if (!shortApps.isEmpty()) addAppPicks(providerFallback.apply(shortApps), shortApps, rows, Recommendations::appCandidate);
        List<Channels.Channel> sorted = new ArrayList<>(rows.values());
        sorted.sort(java.util.Comparator.comparing(row -> row.title, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    static Set<String> shortAppRows(Iterable<Channels.Channel> rows, Set<String> visibleApps) {
        Set<String> shortApps = new HashSet<>(visibleApps);
        shortApps.removeIf(pkg -> !Trending.isStreamingApp(pkg) || !appProvider(pkg));
        for (Channels.Channel row : rows) if (row.items.size() >= APP_LIMIT) shortApps.remove(row.pkg);
        return shortApps;
    }

    static boolean appCandidate(Trending.Pick p) {
        return isAppRow(p.tab) || managedChart(p, Trending.TRENDING) || managedChart(p, Trending.INDIA);
    }

    private static boolean managedChart(Trending.Pick p, String tab) {
        return !p.externalId.isEmpty() && tab.equals(p.tab);
    }

    private static void addAppPicks(List<Trending.Pick> catalog, Set<String> visibleApps,
                                    Map<String, Channels.Channel> rows, Predicate<Trending.Pick> eligible) {
        for (Trending.Pick p : catalog) {
            if (!eligible.test(p) || !visibleApps.contains(p.pkg) || !p.sweep && (p.poster == null || !p.poster.isFile())) continue;
            String key = appKey(p.pkg);
            Channels.Channel row = rows.computeIfAbsent(key, ignored -> {
                Channels.Channel channel = new Channels.Channel();
                channel.key = key;
                channel.pkg = p.pkg;
                channel.title = p.service + " · Recommendations";
                channel.shown = true;
                return channel;
            });
            if (row.items.size() < APP_LIMIT && row.items.stream().noneMatch(item -> sameAppTitle(item, p))) {
                row.items.add(copy(p, "", key));
                row.count = row.items.size();
            }
        }
    }

    private static boolean sameAppTitle(Trending.Pick a, Trending.Pick b) {
        if (identity(a).equals(identity(b))) return true;
        return a.externalId.isEmpty() != b.externalId.isEmpty() && a.kind.equals(b.kind)
                && (a.year.isEmpty() || b.year.isEmpty() || a.year.equals(b.year))
                && normalize(a.title).equals(normalize(b.title));
    }
    private static String identity(Trending.Pick p) { return p.catalogIdentity(); }
    private static Map<String,Trending.Pick> unique(List<Trending.Pick> catalog, boolean includeProviderPicks) {
        Map<String,Trending.Pick> out=new LinkedHashMap<>();
        for(Trending.Pick p:catalog)if(provider(p.pkg)&&(p.sweep||p.image!=null&&p.image.isFile())
                && (Trending.TRENDING.equals(p.tab)||Trending.INDIA.equals(p.tab)||Trending.ENGLISH.equals(p.tab)||Trending.RELEASES.equals(p.tab)
                || includeProviderPicks && p.sweep && isAppRow(p.tab))
                && ("movie".equals(p.kind)||"tv".equals(p.kind))&&p.title!=null&&!p.title.trim().isEmpty()) {
            Trending.Pick old=out.get(identity(p));
            if(old==null||p.editorialRank>0&&(old.editorialRank==0||p.editorialRank<old.editorialRank)
                    ||p.weeklyTrending&&!old.weeklyTrending&&old.editorialRank==0)out.put(identity(p),p);
        }
        if (includeProviderPicks) {
            List<Trending.Pick> identified = new ArrayList<>();
            for (Trending.Pick pick : out.values()) if (pick.sweep && !pick.externalId.isEmpty()) identified.add(pick);
            out.values().removeIf(pick -> !pick.sweep && pick.externalId.isEmpty() && identified.stream()
                    .anyMatch(match -> (pick.pkg.equals(match.pkg) || !pick.year.isEmpty() && pick.year.equals(match.year))
                            && sameAppTitle(pick, match)));
        }
        return out;
    }
    static Channels.Channel build(List<Trending.Pick> catalog,List<Trending.Pick> history,long now) {
        Map<String,Trending.Pick> unique=unique(catalog, true);
        List<Trending.Pick> editorial = new ArrayList<>();
        for (Trending.Pick pick : unique.values()) if (pick.editorialRank > 0) editorial.add(pick);
        if (!editorial.isEmpty()) {
            editorial.sort(java.util.Comparator.comparingInt(pick -> pick.editorialRank));
            editorial = editorial.subList(0, Math.min(10, editorial.size()));
        }
        Map<String,Float> affinity=new HashMap<>(),genres=new HashMap<>();Set<String> watched=new HashSet<>();
        for(Trending.Pick h:history) {
            if(!provider(h.pkg)||h.at<=0||now-h.at>HISTORY_MS)continue;
            float weight=Math.max(.1f,1f-Math.max(0,now-h.at)/(float)HISTORY_MS);
            affinity.merge(h.pkg,weight,Math::max);String title=normalize(h.title);if(title.isEmpty())continue;
            watched.add(title);
            for(Trending.Pick p:unique.values())if(normalize(p.title).equals(title)&&!genre(p).isEmpty())genres.merge(genre(p),weight,Math::max);
        }
        List<Trending.Pick> candidates=new ArrayList<>();Map<String,Float> scores=new HashMap<>();int rank=0;
        Set<String> pinned = new HashSet<>();
        for (Trending.Pick pick : editorial) pinned.add(identity(pick));
        for(Trending.Pick p:unique.values()) {
            float popular=1f-rank++/(float)Math.max(1,unique.size())*.5f;
            if(pinned.contains(identity(p)) || watched.contains(normalize(p.title)))continue;
            long age=releaseAgeDays(p,now);float freshness=age>=0&&age<=90?.8f*(1-age/90f):0;
            candidates.add(p);scores.put(identity(p),popular+(p.weeklyTrending?1.25f:0)+freshness
                    +affinity.getOrDefault(p.pkg,0f)*.75f+genres.getOrDefault(genre(p),0f)*.75f
                    -(isAppRow(p.tab)?3f:0f));
        }
        int slots = LIMIT - editorial.size();
        if (candidates.size() > slots) candidates = rotatingCandidates(candidates, scores, slots, window(now));
        Channels.Channel row=select(KEY,TITLE,candidates,scores,slots);
        row.items.addAll(0, editorial);
        for(int i=0;i<row.items.size();i++) {
            Trending.Pick pick = row.items.get(i);
            row.items.set(i, copy(pick, "", KEY));
        }
        row.count = row.items.size();
        return row;
    }

    private static List<Trending.Pick> rotatingCandidates(List<Trending.Pick> candidates, Map<String,Float> scores,
                                                           int slots, long window) {
        List<Trending.Pick> ranked = select(KEY, TITLE, candidates, scores, candidates.size()).items;
        List<Trending.Pick> english = new ArrayList<>(), other = new ArrayList<>();
        for (Trending.Pick pick : ranked) ("en".equals(pick.lang) ? english : other).add(pick);
        int englishSlots = Math.min(english.size(), Math.max((slots + 1) / 2, slots - other.size()));
        List<Trending.Pick> result = new ArrayList<>(slots);
        addWindow(result, english, englishSlots, window);
        addWindow(result, other, slots - englishSlots, window);
        return result;
    }

    private static void addWindow(List<Trending.Pick> out, List<Trending.Pick> pool, int count, long window) {
        if (count == 0) return;
        int step = Math.max(1, count / 2);
        int start = (int) Math.floorMod(window * step, (long) pool.size());
        for (int i = 0; i < count; i++) out.add(pool.get((start + i) % pool.size()));
    }

    static Channels.Channel newReleases(List<Trending.Pick> catalog,Channels.Channel recommended,long now) {
        Set<String> exclude=new HashSet<>();for(Trending.Pick p:recommended.items)exclude.add(identity(p));
        List<Trending.Pick> all=new ArrayList<>();Map<String,Float> scores=new HashMap<>();
        for(Trending.Pick p:unique(catalog, false).values()) {
            long age=releaseAgeDays(p,now);if(age<0||age>90)continue;
            all.add(p);
            // Prefer distinct rows, but keep enough cards when the local or language-filtered catalog is small.
            scores.put(identity(p),1f-age/90f-(exclude.contains(identity(p)) ? LIMIT : 0));
        }
        Channels.Channel row=select(NEW_KEY,"Recently released",all,scores,LIMIT);
        for(int i=0;i<row.items.size();i++) {
            Trending.Pick pick = row.items.get(i);
            row.items.set(i, copy(pick, "", NEW_KEY));
        }
        return row;
    }
    static long releaseAgeDays(Trending.Pick p,long now) {
        try {
            long today=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay();
            return today-java.time.LocalDate.parse(p.releaseDate).toEpochDay();
        } catch(Exception ignored) {return Long.MIN_VALUE;}
    }
    private static Channels.Channel select(String key,String title,List<Trending.Pick> candidates,Map<String,Float> scores,int limit) {
        Channels.Channel row=new Channels.Channel();row.key=key;row.name=row.title=title;
        List<Trending.Pick> remaining=new ArrayList<>(candidates);Map<String,Integer> used=new HashMap<>();
        Set<String> providers=new HashSet<>();int englishAvailable=0;
        for(Trending.Pick p:remaining){providers.add(p.pkg);if("en".equals(p.lang))englishAvailable++;}
        int target=Math.min(Math.min(6,(limit+1)/2),englishAvailable),english=0;
        int quota=Math.max(4,(limit+Math.max(1,providers.size())-1)/Math.max(1,providers.size()));
        while(row.items.size()<limit&&!remaining.isEmpty()) {
            boolean needEnglish=english<target&&(row.items.size()%2==0||limit-row.items.size()<=target-english);
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
    static Trending.Pick copy(Trending.Pick p,String reason,String tab) {
        Trending.Pick out=new Trending.Pick();out.id=p.id;out.kind=p.kind;out.title=p.title;out.pkg=p.pkg;out.service=p.service;
        out.tab=tab;out.lang=p.lang;out.image=p.image;out.poster=p.poster;
        out.backdropPath=p.backdropPath;out.posterPath=p.posterPath;
        String metadata=p.meta==null?"":p.meta,suffix=" · "+p.service;
        if (metadata.equals(p.service)) metadata = "";
        else if (metadata.endsWith(suffix)) metadata = metadata.substring(0, metadata.length() - suffix.length());
        out.intentUri = p.intentUri;
        out.meta = reason.isEmpty() ? metadata : metadata.isEmpty() ? reason : reason + " · " + metadata;
        out.overview=p.overview;out.year=p.year;out.rating=p.rating;out.releaseDate=p.releaseDate;out.weeklyTrending=p.weeklyTrending;
        out.externalId=p.externalId;out.cast=p.cast;out.ratingSource=p.ratingSource;out.market=p.market;
        out.sweep=p.sweep;out.editorialRank=p.editorialRank;
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
