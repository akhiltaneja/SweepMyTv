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
        for(Trending.Pick p:row.items){assertNotEquals(0,p.id);assertEquals("Film · Drama", p.meta);}
        assertEquals(.4f,watched.progress,0);assertEquals("app://resume",watched.intentUri);
        watched.at=NOW-91L*24*3600_000;
        assertTrue(java.util.stream.LongStream.range(0, 6).anyMatch(i ->
                Recommendations.build(catalog, List.of(watched), NOW + i * Recommendations.WINDOW_MS)
                        .items.stream().anyMatch(p -> p.id == 0)));
    }
    @Test public void emptyCatalogHasNoInventedRecommendationsAndColdStartShowsGenres() throws Exception {
        assertTrue(Recommendations.build(Collections.emptyList(),Collections.emptyList(),NOW).items.isEmpty());
        Trending.Pick p=pick(1,PROVIDERS[3],folder.newFile());
        Channels.Channel row=Recommendations.build(Collections.singletonList(p),Collections.emptyList(),NOW);
        assertEquals(1,row.items.size());assertEquals("Film · Drama", row.items.get(0).meta);assertTrue(Recommendations.provider(PROVIDERS[3]));
    }

    @Test public void catalogRotatesAcrossWindowsWhileEditorialPinsAndProviderEligibilityStayIntact() throws Exception {
        File art = folder.newFile();
        List<Trending.Pick> catalog = new ArrayList<>();
        for (int i = 0; i < 46; i++) {
            Trending.Pick candidate = pick(i, PROVIDERS[i % PROVIDERS.length], art);
            candidate.lang = i % 3 == 0 ? "en" : "hi";
            candidate.externalId = "tt" + (100 + i);
            candidate.sweep = true;
            if (i == 0) candidate.editorialRank = 1;
            if (i > 17) candidate.tab = Recommendations.appKey(candidate.pkg);
            catalog.add(candidate);
        }
        long next = NOW + Recommendations.WINDOW_MS;
        List<Integer> first = Recommendations.build(catalog, List.of(), NOW).items.stream()
                .map(p -> p.id).collect(java.util.stream.Collectors.toList());
        List<Integer> later = Recommendations.build(catalog, List.of(), next).items.stream()
                .map(p -> p.id).collect(java.util.stream.Collectors.toList());
        assertEquals(Recommendations.LIMIT, first.size());
        assertEquals(Recommendations.LIMIT, later.size());
        assertEquals(Integer.valueOf(0), first.get(0));
        assertEquals(Integer.valueOf(0), later.get(0));
        assertNotEquals(new HashSet<>(first), new HashSet<>(later));
        assertTrue(first.stream().anyMatch(id -> id > 17) || later.stream().anyMatch(id -> id > 17));
    }

    @Test public void aPreviousWindowSuccessDoesNotBlockARefreshAtTheBoundary() {
        long offset = java.util.TimeZone.getDefault().getOffset(NOW);
        long start = Recommendations.window(NOW) * Recommendations.WINDOW_MS - offset
                + Trending.FEED_PUBLISH_DELAY_MS;
        assertTrue(Trending.recommendationFetchDue(start, start - 1, start - 1));
        assertFalse(Trending.recommendationFetchDue(start + 60_000, start, start));
        assertFalse(Trending.recommendationFetchDue(start + 60_000, 0, start));
        assertTrue(Trending.recommendationFetchDue(start + 30 * 60_000L, 0, start));
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
        for(Trending.Pick p:fresh.items){assertFalse(seen.contains(p.kind+":"+p.id));assertTrue(p.id<40);assertEquals("Film · Drama", p.meta);assertEquals(Recommendations.NEW_KEY,p.tab);}
        assertTrue(fresh.items.stream().filter(p->"en".equals(p.lang)).count()>=6);
        assertTrue(Recommendations.newReleases(Collections.singletonList(catalog.get(42)),new Channels.Channel(),NOW).items.isEmpty());
    }
    @Test public void verifiedWeeklyTrendsAndRecentDebutsOutrankOldPopularity() throws Exception {
        File art=folder.newFile();Trending.Pick classic=pick(1,PROVIDERS[2],art),weekly=pick(2,PROVIDERS[0],art),recent=pick(3,PROVIDERS[1],art);
        for(Trending.Pick p:Arrays.asList(classic,weekly,recent))p.lang="en";
        weekly.weeklyTrending=true;weekly.tab=Trending.ENGLISH;recent.releaseDate=date(-2);recent.tab=Trending.RELEASES;
        Channels.Channel row=Recommendations.build(Arrays.asList(classic,weekly,recent),Collections.emptyList(),NOW);
        assertEquals(weekly.id,row.items.get(0).id);assertEquals(recent.id,row.items.get(1).id);
        assertEquals("Film · Drama", row.items.get(0).meta);
        assertEquals("Film · Drama", row.items.get(1).meta);
    }

    @Test public void recentReleasesFillShortRowsWithEligibleTitlesAlreadyRecommended() throws Exception {
        File art = folder.newFile();
        List<Trending.Pick> catalog = new ArrayList<>();
        Channels.Channel recommended = new Channels.Channel();
        for (int i = 0; i < 6; i++) {
            Trending.Pick title = pick(i, PROVIDERS[i % PROVIDERS.length], art);
            title.lang = "en";
            title.releaseDate = date(-i);
            title.tab = Trending.INDIA;
            catalog.add(title);
            if (i < 5) recommended.items.add(title);
        }
        Channels.Channel recent = Recommendations.newReleases(catalog, recommended, NOW);
        assertEquals(6, recent.items.size());
        assertEquals(5, recent.items.get(0).id);
    }

    @Test public void managedAppRowsUseBothChartsWithoutDuplicates() throws Exception {
        File poster = folder.newFile();
        String netflix = PROVIDERS[1];
        Trending.Pick trending = pick(1, netflix, poster);
        trending.externalId = "tt100";
        trending.poster = poster;
        Trending.Pick repeat = Recommendations.copy(trending, "", Trending.INDIA);
        Trending.Pick recent = pick(2, netflix, poster);
        recent.externalId = "tt200";
        recent.tab = Trending.INDIA;
        recent.poster = poster;
        List<Channels.Channel> rows = Recommendations.appRows(List.of(repeat, trending, trending, recent), Set.of(netflix));
        assertEquals(1, rows.size());
        assertEquals(List.of("tt100", "tt200"), rows.get(0).items.stream().map(p -> p.externalId).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void englishNetflixOnlyFillsPastThePinsWithoutUsingOtherAppsOrOldReleases() throws Exception {
        List<Trending.Pick> feed = new ArrayList<>();
        File art = folder.newFile();
        String netflix = PROVIDERS[1];
        for (int i = 0; i < 16; i++) {
            Trending.Pick p = pick(i, netflix, art);
            p.externalId = "tt" + (100 + i);
            p.sweep = true;
            p.lang = "en";
            p.releaseDate = date(i < 2 ? -i : -365);
            if (i >= 2) p.tab = Recommendations.appKey(netflix);
            feed.add(p);
        }
        feed.get(0).editorialRank = 1;
        Trending.Pick otherApp = pick(90, PROVIDERS[0], art);
        otherApp.lang = "en";
        feed.add(otherApp);
        Trending.Pick hindi = pick(91, netflix, art);
        hindi.lang = "hi";
        feed.add(hindi);
        List<Trending.Pick> installed = new ArrayList<>();
        for (Trending.Pick p : feed) if (netflix.equals(p.pkg)) installed.add(p);
        List<Trending.Pick> scoped = ContentLanguages.filter(installed, Set.of("en"));
        Channels.Channel recommended = Recommendations.build(scoped, Collections.emptyList(), NOW);
        assertEquals(Recommendations.LIMIT, recommended.items.size());
        assertEquals(0, recommended.items.get(0).id);
        assertTrue(recommended.items.stream().allMatch(p -> netflix.equals(p.pkg) && "en".equals(p.lang)));
        assertEquals(2, Recommendations.newReleases(scoped, recommended, NOW).items.size());
        assertEquals(12, Recommendations.appRows(scoped, Set.of(netflix)).get(0).items.size());
    }

    @Test public void shortPrimeRowKeepsPrimaryPicksAndFillsFromItsOwnLanguageAndApp() throws Exception {
        File poster = folder.newFile();
        String prime = PROVIDERS[3];
        Trending.Pick first = pick(1, prime, poster);
        first.externalId = "tt100";
        first.lang = "en";
        first.sweep = true;
        Trending.Pick recent = Recommendations.copy(first, "", Trending.INDIA);
        recent.id = 2;
        recent.title = "Recent";
        recent.externalId = "tt200";
        Trending.Pick duplicate = Recommendations.copy(first, "", Recommendations.appKey(prime));
        duplicate.id = 3;
        duplicate.externalId = "";
        Trending.Pick hindi = Recommendations.copy(first, "", Recommendations.appKey(prime));
        hindi.lang = "hi";
        hindi.title = "Hindi title";
        hindi.externalId = "tt900";
        List<Trending.Pick> fallback = new ArrayList<>(List.of(duplicate, hindi, pick(90, PROVIDERS[1], poster)));
        for (int i = 10; i < 25; i++) {
            Trending.Pick candidate = pick(i, prime, poster);
            candidate.externalId = "tt" + (1000 + i);
            candidate.sweep = true;
            candidate.lang = "en";
            fallback.add(candidate);
        }
        List<Channels.Channel> rows = Recommendations.appRows(List.of(first, recent), Set.of(prime), apps -> {
            assertEquals(Set.of(prime), apps);
            return ContentLanguages.filter(fallback, Set.of("en"));
        });
        List<Trending.Pick> cards = rows.get(0).items;
        assertEquals(1, rows.size());
        assertEquals(12, cards.size());
        assertEquals(12, rows.get(0).count);
        assertEquals(List.of(1, 2, 10), cards.subList(0, 3).stream().map(p -> p.id)
                .collect(java.util.stream.Collectors.toList()));
        assertTrue(cards.stream().allMatch(p -> prime.equals(p.pkg) && "en".equals(p.lang)));
        assertEquals(1, Recommendations.appRows(cards, Set.of(prime), apps -> {
            throw new AssertionError("A full row should not open the fallback catalog");
        }).size());
    }

    @Test public void artworkQueueStartsEachProviderAndShelfBeforeDeeperRows() throws Exception {
        File poster = folder.newFile();
        Trending.Pick appleFirst = pick(1, PROVIDERS[0], poster);
        Trending.Pick appleLater = pick(2, PROVIDERS[0], poster);
        Trending.Pick netflix = pick(3, PROVIDERS[1], poster);
        Trending.Pick appleRecent = pick(4, PROVIDERS[0], poster);
        appleRecent.tab = Trending.INDIA;
        assertEquals(List.of(appleFirst, netflix, appleRecent, appleLater),
                Trending.artworkOrder(List.of(appleFirst, appleLater, netflix, appleRecent)));
    }

    @Test public void appRowsAreSeparateFromGlobalRecommendationsAndRespectVisibleApps() throws Exception {
        String sony = "com.sonyliv", crunchyroll = "com.crunchyroll.crunchyroid";
        File poster = folder.newFile();
        Trending.Pick drama = pick(1, sony, poster), anime = pick(1, crunchyroll, poster);
        drama.tab = Recommendations.appKey(sony);
        drama.service = "SonyLIV";
        drama.meta = "Film · Drama · SonyLIV";
        drama.poster = poster;
        anime.tab = Recommendations.appKey(crunchyroll);
        anime.service = "Crunchyroll";
        anime.poster = poster;
        Trending.Pick still = pick(2, sony, poster);
        still.tab = drama.tab;
        List<Trending.Pick> catalog = Arrays.asList(drama, anime, still);
        List<Channels.Channel> rows = Recommendations.appRows(catalog, new HashSet<>(Arrays.asList(sony, crunchyroll)));
        assertEquals(2, rows.size());
        assertEquals("Crunchyroll · Recommendations", rows.get(0).title);
        assertEquals(drama.id, rows.get(1).items.get(0).id);
        assertEquals("Film · Drama", rows.get(1).items.get(0).meta);
        assertEquals(1, Recommendations.appRows(catalog, Collections.singleton(sony)).size());
        assertTrue(Recommendations.build(catalog, Collections.emptyList(), NOW).items.isEmpty());
        assertFalse(Recommendations.appProvider(Cricket.FANCODE));
    }
}
