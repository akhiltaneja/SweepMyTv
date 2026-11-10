package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class HomeInsightsTest {
    private static final long NOW=Instant.parse("2026-11-03T08:00:00Z").toEpochMilli();
    @Test public void airIsExplicitlyEstimatedUsesCorrectScaleAndExpires()throws Exception{
        HomeInsights.Air air=HomeInsights.parseAir(new JSONObject("{\"timezone\":\"Asia/Kolkata\",\"current\":{\"us_aqi\":185,\"pm2_5\":73.6,\"time\":\"2026-11-03T13:30\"}}"),ZoneId.of("UTC"));
        assertEquals(185,air.aqi);assertEquals("Unhealthy",air.label());assertEquals(NOW,air.at);assertTrue(air.valid(NOW));assertFalse(air.valid(NOW+13*HomeInsights.HOUR));assertFalse(air.valid(NOW-HomeInsights.HOUR));
        for(String invalid:new String[]{"null","-1","2000"})try{HomeInsights.parseAir(new JSONObject("{\"current\":{\"us_aqi\":"+invalid+",\"time\":\"2026-11-03T13:30\"}}"),ZoneId.of("UTC"));fail();}catch(Exception expected){}
        HomeInsights.State state=new HomeInsights.State();state.place=delhi();state.air=air;Channels.Channel row=state.row(RuntimeEnvironment.getApplication(),NOW);HomeInsights.Card card=(HomeInsights.Card)row.items.get(0).extra;
        assertTrue(card.foot.contains("US AQI"));assertTrue(card.foot.contains("estimate"));assertTrue(card.detail.contains("not an AQI.in station"));assertTrue(card.detail.contains("73.6"));
    }
    @Test public void rssUsesRealDatesSafePublisherLinksAndUsRelevantTopics()throws Exception{
        String xml="<rss><channel>"+item("OpenAI announces a new AI model","https://www.wsj.com/tech/ai/one","Tue, 03 Nov 2026 07:00:00 GMT")+item("Local India festival celebration","https://www.wsj.com/two","Tue, 03 Nov 2026 07:00:00 GMT")+item("Apple launches a new chip","https://evil.example/story","Tue, 03 Nov 2026 07:00:00 GMT")+item("Microsoft future headline","https://www.wsj.com/next","Wed, 04 Nov 2026 07:00:00 GMT")+item("Apple old headline","https://www.wsj.com/old","Tue, 03 Oct 2026 07:00:00 GMT")+"</channel></rss>";
        List<HomeInsights.Story> stories=HomeInsights.parseFeed(xml,"WSJ Tech","Tech","wsj.com",NOW);assertEquals(1,stories.size());assertEquals("AI",stories.get(0).topic);assertEquals("WSJ Tech",stories.get(0).source);
        String atom="<feed xmlns='http://www.w3.org/2005/Atom'><entry><title>Microsoft releases software</title><link href='https://www.cnbc.com/story'/><updated>2026-11-03T07:00:00Z</updated></entry></feed>";assertEquals(1,HomeInsights.parseFeed(atom,"CNBC Tech","Tech","cnbc.com",NOW).size());
        assertEquals("",HomeInsights.safeUrl("https://wsj.com.evil.example/article","wsj.com"));assertEquals("",HomeInsights.safeUrl("javascript:alert(1)","wsj.com"));
        try{HomeInsights.parseFeed("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss/>","WSJ","Tech","wsj.com",NOW);fail();}catch(java.io.IOException expected){}
    }
    @Test public void holidaysFilterOtherRegionsDeduplicateAndUseLocalCalendarDates(){
        String ics="BEGIN:VCALENDAR\r\n"+event("Delhi: Diwali (Regional Holiday)","20261108","India: Delhi")+event("Delhi: Diwali (Regional Holiday)","20261108","India: Delhi")+event("Tamil Nadu: Local holiday (Regional Holiday)","20261104","India: Tamil Nadu")+event("India: Old festival","20261001","India")+event("Delhi: Guru Nanak's Birth\r\n day (Regional Holiday)","20261124","India: Delhi")+"END:VCALENDAR";
        List<HomeInsights.Holiday> list=HomeInsights.parseHolidays(ics,LocalDate.of(2026,11,3),"Delhi");assertEquals(2,list.size());assertEquals("Diwali",list.get(0).title);assertEquals("2026-11-08",list.get(0).date);assertEquals("Guru Nanak's Birthday",list.get(1).title);
        assertEquals(0,HomeInsights.parseHolidays(ics,LocalDate.of(2026,11,3),"California").size());
        assertEquals("usa",HomeInsights.countrySlug("US"));assertEquals("united-kingdom",HomeInsights.countrySlug("GB"));assertEquals("india",HomeInsights.countrySlug("IN"));assertEquals("delhi",HomeInsights.slug("National Capital Territory of Delhi"));
    }
    @Test public void locationChangesNeverShowAnotherCitysAirOrFestivals()throws Exception{
        HomeInsights.State state=new HomeInsights.State();state.locationKey="Delhi";state.place=delhi();state.air=new HomeInsights.Air(185,73,NOW);state.holidays.add(new HomeInsights.Holiday("Diwali","https://www.officeholidays.com/day","2026-11-08"));state.stories.add(new HomeInsights.Story("OpenAI news","https://www.wsj.com/story","WSJ Tech","AI",NOW));
        HomeInsights.State next=state.forLocation("Paris");assertNull(next.place);assertNull(next.air);assertTrue(next.holidays.isEmpty());assertEquals(1,next.stories.size());assertNotNull(state.air);
        HomeInsights.State restored=HomeInsights.State.parse(state.json());assertEquals("IN",restored.place.country);assertEquals(185,restored.air.aqi);assertEquals(1,restored.stories.size());
    }
    @Test public void cardsRespectTogglesAndNewsDoesNotDependOnMovieLanguages(){
        Context c=RuntimeEnvironment.getApplication();HomeInsights.State state=new HomeInsights.State();state.place=delhi();state.air=new HomeInsights.Air(100,40,NOW);state.holidays.add(new HomeInsights.Holiday("Diwali","https://www.officeholidays.com/day","2026-11-08"));
        state.stories.add(new HomeInsights.Story("OpenAI news","https://www.wsj.com/story","WSJ Tech","AI",NOW));new Trending(c).setLanguages(Set.of("hi"));
        Channels.Channel row=state.row(c,NOW);assertEquals(3,row.items.size());assertEquals("news",((HomeInsights.Card)row.items.get(1).extra).kind);assertEquals(1,((HomeInsights.Card)row.items.get(1).extra).stories.size());assertTrue(((HomeInsights.Card)row.items.get(2).extra).foot.contains("5 days"));
        HomeOptions.prefs(c).edit().putBoolean("insight_air",false).putBoolean("insight_source_wsj",false).apply();row=state.row(c,NOW);assertEquals(2,row.items.size());assertTrue(((HomeInsights.Card)row.items.get(0).extra).stories.isEmpty());
    }
    @Test public void duplicateHeadlinesAndStaleOrFutureStoriesCannotFillTheNewsCard(){
        List<HomeInsights.Story> input=new ArrayList<>();input.add(new HomeInsights.Story("Apple releases a chip","https://www.wsj.com/a","WSJ Tech","Tech",NOW));input.add(new HomeInsights.Story("Apple releases a chip!","https://www.cnbc.com/a","CNBC Tech","Tech",NOW-100));input.add(new HomeInsights.Story("Microsoft future","https://www.wsj.com/b","WSJ Tech","Tech",NOW+HomeInsights.HOUR));
        assertEquals(1,HomeInsights.mergeStories(input,NOW).size());
    }
    @Test public void structuredIpLocationProvidesCountryAndRegionWithoutKeepingAnIp()throws Exception{
        JSONObject reply=new JSONObject("{\"ip\":\"192.0.2.1\",\"location\":{\"city\":\"Delhi\",\"country_code\":\"IN\",\"state\":\"Delhi\",\"latitude\":28.6139,\"longitude\":77.209,\"timezone\":\"Asia/Kolkata\"}}");
        ApproximateCity city=ApproximateCity.parse(reply,ZoneId.of("Asia/Kolkata"),NOW);assertNotNull(city);assertEquals("IN",city.country);assertEquals("Delhi",city.region);assertEquals(28.61,city.lat,.001);assertNull(ApproximateCity.parse(reply,ZoneId.of("America/New_York"),NOW));
        assertNull(ApproximateCity.parse(reply.put("is_bogon",true),ZoneId.of("Asia/Kolkata"),NOW));
    }
    @Test public void snapshotUpdatesWhenHeadlineSourceOrAqiChanges(){
        Context c=RuntimeEnvironment.getApplication();HomeInsights.State state=new HomeInsights.State();state.place=delhi();state.air=new HomeInsights.Air(80,30,NOW);state.stories.add(new HomeInsights.Story("Apple launches a chip","https://www.wsj.com/a","WSJ Tech","Tech",NOW));String before=new ChannelSnapshot(List.of(state.row(c,NOW))).signature;
        state.air=new HomeInsights.Air(180,70,NOW);assertNotEquals(before,new ChannelSnapshot(List.of(state.row(c,NOW))).signature);
    }
    @Test public void mixedFeedsCannotTreatPronounUsOrGeneralPoliticsAsTech()throws Exception{
        String xml="<rss><channel>"+item("Live Q&amp;A: Ask Us Your Questions About the Midterm-Elections Results","https://www.wsj.com/politics","Tue, 03 Nov 2026 07:00:00 GMT")+item("US Senate election results announced","https://www.wsj.com/election","Tue, 03 Nov 2026 07:00:00 GMT")+item("Apple launches a new chip","https://www.wsj.com/chip","Tue, 03 Nov 2026 07:00:00 GMT")+"</channel></rss>";
        List<HomeInsights.Story> list=HomeInsights.parseFeed(xml,"WSJ Tech","Tech","wsj.com",NOW);assertEquals(1,list.size());assertEquals("Apple launches a new chip",list.get(0).title);
        assertFalse(HomeInsights.usFocused("Ask Us your questions"));assertTrue(HomeInsights.usFocused("U.S. stocks rise"));assertTrue(HomeInsights.usFocused("US stocks rise"));
        Context c=RuntimeEnvironment.getApplication();HomeInsights.State cached=new HomeInsights.State();cached.stories.add(new HomeInsights.Story("Ask Us about the election results","https://www.wsj.com/old","WSJ Tech","Tech",NOW));cached.stories.add(list.get(0));
        HomeInsights.Card news=(HomeInsights.Card)cached.row(c,NOW).items.get(1).extra;assertEquals(1,news.stories.size());assertEquals("Apple launches a new chip",news.stories.get(0).title);
    }
    private static HomeInsights.Place delhi(){return new HomeInsights.Place("Delhi","IN","Delhi","Asia/Kolkata",28.61,77.21,false);}
    private static String item(String title,String url,String date){return "<item><title>"+title+"</title><link>"+url+"</link><pubDate>"+date+"</pubDate></item>";}
    private static String event(String title,String date,String location){return "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:"+date+"\r\nSUMMARY:"+title+"\r\nLOCATION:"+location+"\r\nURL:https://www.officeholidays.com/holidays/india/example\r\nEND:VEVENT\r\n";}
}
