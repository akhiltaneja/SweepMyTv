package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.*;

public class RecommendationsTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    static final long NOW=1_800_000_000_000L;
    static final String[] PROVIDERS={"com.apple.atve.androidtv.appletv","com.netflix.ninja","in.startv.hotstar","com.amazon.amazonvideo.livingroom"};
    static Trending.Pick pick(int id,String pkg,File art) {
        Trending.Pick p=new Trending.Pick();p.id=id;p.kind=id%2==0?"tv":"movie";p.title="Title "+id;
        p.pkg=pkg;p.service=pkg;p.tab=Trending.TRENDING;p.image=art;p.meta="Film · Drama · "+pkg;
        p.overview="A catalog synopsis";p.year="2026";p.rating=8.2f;return p;
    }
    @Test public void aggregatesMoviesAndShowsAcrossProvidersWithoutDuplicatesOrYoutube() throws Exception {
        File art=folder.newFile();List<Trending.Pick> catalog=new ArrayList<>();
        for(int i=0;i<28;i++)catalog.add(pick(i,PROVIDERS[i%4],art));
        catalog.add(catalog.get(0));catalog.add(pick(90,"com.google.android.youtube.tv",art));
        Trending.Pick missing=pick(92,PROVIDERS[0],new File(folder.getRoot(),"missing.jpg"));catalog.add(missing);
        Channels.Channel row=Recommendations.build(catalog,Collections.emptyList(),NOW);
        assertEquals(12,row.items.size());Set<String> ids=new HashSet<>(),apps=new HashSet<>(),kinds=new HashSet<>();
        for(Trending.Pick p:row.items){assertTrue(ids.add(p.kind+":"+p.id));apps.add(p.pkg);kinds.add(p.kind);
            assertNotEquals(90,p.id);assertNotEquals(92,p.id);assertEquals("A catalog synopsis",p.overview);assertEquals(8.2f,p.rating,0);assertEquals(-1,p.progress,0);}
        assertEquals(4,apps.size());assertEquals(new HashSet<>(Arrays.asList("movie","tv")),kinds);
        assertEquals(Trending.TRENDING,catalog.get(0).tab);assertEquals("Film · Drama · "+PROVIDERS[0],catalog.get(0).meta);
    }
    @Test public void excludesRecentMatchesAndUsesLocalGenreAffinityWithoutMutatingHistory() throws Exception {
        File art=folder.newFile();List<Trending.Pick> catalog=new ArrayList<>();
        for(int i=0;i<16;i++)catalog.add(pick(i,PROVIDERS[i%4],art));
        Trending.Pick watched=pick(0,PROVIDERS[0],art);watched.title="Title 0 Season 2";watched.at=NOW-1000;watched.progress=.4f;watched.intentUri="app://resume";
        Channels.Channel row=Recommendations.build(catalog,Collections.singletonList(watched),NOW);
        for(Trending.Pick p:row.items){assertNotEquals(0,p.id);assertTrue(p.meta.startsWith("More Drama"));}
        assertEquals(.4f,watched.progress,0);assertEquals("app://resume",watched.intentUri);
        watched.at=NOW-91L*24*3600_000;
        assertTrue(Recommendations.build(catalog,Collections.singletonList(watched),NOW).items.stream().anyMatch(p->p.id==0));
    }
    @Test public void emptyCatalogHasNoInventedRecommendationsAndColdStartIsLabelledPopular() throws Exception {
        assertTrue(Recommendations.build(Collections.emptyList(),Collections.emptyList(),NOW).items.isEmpty());
        Trending.Pick p=pick(1,PROVIDERS[3],folder.newFile());
        Channels.Channel row=Recommendations.build(Collections.singletonList(p),Collections.emptyList(),NOW);
        assertEquals(1,row.items.size());assertTrue(row.items.get(0).meta.startsWith("Popular on "));assertTrue(Recommendations.provider(PROVIDERS[3]));
    }
    private static String date(int days) {return java.time.Instant.ofEpochMilli(NOW).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(days).toString();}
    @Test public void englishHasReservedPlacesEvenWhenLocalPopularityAndProviderQuotaCompete() throws Exception {
        File art=folder.newFile();List<Trending.Pick> catalog=new ArrayList<>();
        for(int i=0;i<30;i++){Trending.Pick p=pick(i,PROVIDERS[i%4],art);p.lang="hi";catalog.add(p);}
        for(int i=30;i<36;i++){Trending.Pick p=pick(i,PROVIDERS[1],art);p.lang="en";p.tab=Trending.ENGLISH;p.weeklyTrending=true;catalog.add(p);}
        Channels.Channel row=Recommendations.build(catalog,Collections.emptyList(),NOW);
        assertEquals(12,row.items.size());assertEquals("en",row.items.get(0).lang);
        assertEquals(6,row.items.stream().filter(p->"en".equals(p.lang)).count());
        assertTrue(row.items.stream().anyMatch(p->!"en".equals(p.lang)));
    }
    @Test public void newReleasesUseActualDebutDatesAndStayDistinctWhenEnoughTitlesExist() throws Exception {
        File art=folder.newFile();List<Trending.Pick> catalog=new ArrayList<>();
        for(int i=0;i<40;i++){Trending.Pick p=pick(i,PROVIDERS[i%4],art);p.lang=i%2==0?"en":"hi";p.releaseDate=date(-i);p.tab=Trending.RELEASES;catalog.add(p);}
        for(int i=40;i<43;i++){Trending.Pick p=pick(i,PROVIDERS[0],art);p.lang="en";p.releaseDate=i==40?date(1):i==41?date(-5000):"";catalog.add(p);}
        Channels.Channel recommended=Recommendations.build(catalog,Collections.emptyList(),NOW);
        Channels.Channel fresh=Recommendations.newReleases(catalog,recommended,NOW);assertEquals(12,fresh.items.size());
        Set<String> seen=new HashSet<>();for(Trending.Pick p:recommended.items)seen.add(p.kind+":"+p.id);
        for(Trending.Pick p:fresh.items){assertFalse(seen.contains(p.kind+":"+p.id));assertTrue(p.id<40);assertTrue(p.meta.startsWith("Premiered "));assertEquals(Recommendations.NEW_KEY,p.tab);}
        assertTrue(fresh.items.stream().filter(p->"en".equals(p.lang)).count()>=6);
        assertTrue(Recommendations.newReleases(Collections.singletonList(catalog.get(42)),new Channels.Channel(),NOW).items.isEmpty());
    }
    @Test public void verifiedWeeklyTrendsAndRecentDebutsOutrankOldPopularity() throws Exception {
        File art=folder.newFile();Trending.Pick classic=pick(1,PROVIDERS[2],art),weekly=pick(2,PROVIDERS[0],art),recent=pick(3,PROVIDERS[1],art);
        for(Trending.Pick p:Arrays.asList(classic,weekly,recent))p.lang="en";
        weekly.weeklyTrending=true;weekly.tab=Trending.ENGLISH;recent.releaseDate=date(-2);recent.tab=Trending.RELEASES;
        Channels.Channel row=Recommendations.build(Arrays.asList(classic,weekly,recent),Collections.emptyList(),NOW);
        assertEquals(weekly.id,row.items.get(0).id);assertEquals(recent.id,row.items.get(1).id);
        assertTrue(row.items.get(0).meta.startsWith("Trending this week"));assertTrue(row.items.get(1).meta.startsWith("Recent release"));
    }
}
