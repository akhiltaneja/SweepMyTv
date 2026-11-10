package com.sweepmytv.launcher;

import android.content.*;
import android.os.*;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** Small, source-labelled home cards. Fetching/parsing stays off the UI thread; no article bodies or images. */
final class HomeInsights {
    static final String KEY="sweep_insights";
    static final long HOUR=3600_000L;
    static final class Place {
        final String city,country,region,zone;final double lat,lon;final boolean approximate;
        Place(String city,String country,String region,String zone,double lat,double lon,boolean approximate){this.city=city;this.country=country;this.region=region;this.zone=zone;this.lat=lat;this.lon=lon;this.approximate=approximate;}
        ZoneId timeZone(){try{return ZoneId.of(zone);}catch(Exception e){return ZoneId.systemDefault();}}
        String key(){return country+"|"+region+"|"+lat+"|"+lon;}
        JSONObject json()throws Exception{return new JSONObject().put("city",city).put("country",country).put("region",region).put("zone",zone).put("lat",lat).put("lon",lon).put("approximate",approximate);}
        static Place parse(JSONObject o){return o==null?null:new Place(o.optString("city"),o.optString("country"),o.optString("region"),o.optString("zone"),o.optDouble("lat"),o.optDouble("lon"),o.optBoolean("approximate"));}
    }
    static final class Story {
        final String title,url,source,topic;final long published;
        Story(String title,String url,String source,String topic,long published){this.title=title;this.url=url;this.source=source;this.topic=topic;this.published=published;}
        JSONObject json()throws Exception{return new JSONObject().put("title",title).put("url",url).put("source",source).put("topic",topic).put("published",published);}
        static Story parse(JSONObject o){return new Story(o.optString("title"),o.optString("url"),o.optString("source"),o.optString("topic"),o.optLong("published"));}
    }
    static final class Holiday {
        final String title,url,date;Holiday(String title,String url,String date){this.title=title;this.url=url;this.date=date;}
        JSONObject json()throws Exception{return new JSONObject().put("title",title).put("url",url).put("date",date);}
        static Holiday parse(JSONObject o){return new Holiday(o.optString("title"),o.optString("url"),o.optString("date"));}
    }
    static final class Air {
        final int aqi;final double pm25;final long at;
        Air(int aqi,double pm25,long at){this.aqi=aqi;this.pm25=pm25;this.at=at;}
        boolean valid(long now){return aqi>=0&&at>0&&at<=now+15*60_000L&&now-at<=12*HOUR;}
        String label(){return aqi<=50?"Good":aqi<=100?"Moderate":aqi<=150?"Unhealthy for sensitive groups":aqi<=200?"Unhealthy":aqi<=300?"Very unhealthy":"Hazardous";}
        JSONObject json()throws Exception{return new JSONObject().put("aqi",aqi).put("pm25",Double.isFinite(pm25)?pm25:JSONObject.NULL).put("at",at);}
        static Air parse(JSONObject o){return o==null?null:new Air(o.optInt("aqi",-1),o.optDouble("pm25",Double.NaN),o.optLong("at"));}
    }
    static final class Card {
        String kind,title,kicker,metric,body,foot,url,detail;int color=0xFFBDB6DF;long target;
        final List<Story> stories=new ArrayList<>();
    }
    static final class State {
        Place place;Air air;List<Story> stories=new ArrayList<>();List<Holiday> holidays=new ArrayList<>();
        String airError="",holidayError="",calendarUrl="",locationKey="";final Map<String,String> sources=new LinkedHashMap<>();
        static State empty(){return new State();}
        State forLocation(String key){if(key.equals(locationKey))return this;State out=new State();out.locationKey=key;out.stories=new ArrayList<>(stories);out.sources.putAll(sources);return out;}
        Channels.Channel row(Context ctx,long now){
            Channels.Channel row=new Channels.Channel();row.key=KEY;row.title=row.name="At a glance";
            android.content.SharedPreferences p=HomeOptions.prefs(ctx);
            if(p.getBoolean("insight_air",true)){
                Card c=new Card();c.kind="air";c.kicker="AIR · "+(place==null?"YOUR LOCATION":place.city.toUpperCase(Locale.ROOT));c.title="Local air quality";c.url="https://www.aqi.in/";
                boolean valid=place!=null&&air!=null&&air.valid(now);c.metric=valid?String.valueOf(air.aqi):"—";
                c.body=valid?air.label():place==null?"Set a weather location":"Estimate unavailable";
                c.foot=valid?(now-air.at>3*HOUR||!airError.isEmpty()?"Cached · ":"")+"US AQI · model estimate":"Open-Meteo · CAMS";
                c.color=valid?air.aqi<=50?0xFF9ED3A5:air.aqi<=100?0xFFE9D48B:air.aqi<=150?0xFFF4B16B:air.aqi<=200?0xFFF28E85:air.aqi<=300?0xFFCAA2DF:0xFFDF93AD:0xFFAAB6CC;
                c.detail="Open-Meteo / CAMS model estimate, not an AQI.in station reading.\nUS AQI scale; it is different from the Indian AQI scale.\n"+(place==null?"Choose a city in Connections → Keys and location.":place.city+(place.approximate?" · Approximate IP location":"")+"\n")+(valid?"Estimate time: "+time(air.at)+"\nPM2.5: "+(Double.isFinite(air.pm25)?String.format(Locale.US,"%.1f µg/m³",air.pm25):"Unavailable"):airError.isEmpty()?"Waiting for an estimate":airError);
                add(row,c);
            }
            if(p.getBoolean("insight_news",true)){
                Card c=new Card();c.kind="news";c.kicker="US · AI / TECH / MARKETS";c.title="AI, tech & markets";
                for(Story s:stories)if(freshStory(s,now)&&eligibleStory(s)&&sourceEnabled(ctx,s.source))c.stories.add(s);
                Story lead=c.stories.isEmpty()?null:c.stories.get((int)Math.floorMod(now/120_000L,(long)c.stories.size()));
                c.metric=lead==null?"No fresh headlines":lead.title;c.body=lead==null?"Connect to refresh publisher feeds":lead.source+" · "+lead.topic;c.foot=lead==null?"Publisher headlines · OK to browse":time(lead.published)+" · OK to browse";
                StringBuilder info=new StringBuilder();for(Map.Entry<String,String> e:sources.entrySet())info.append(e.getKey()).append(": ").append(e.getValue()).append('\n');c.detail=info.toString();add(row,c);
            }
            if(p.getBoolean("insight_festivals",true)){
                Card c=new Card();c.kind="festival";c.kicker="NEXT HOLIDAY / FESTIVAL";c.title="Upcoming celebrations";c.metric="No calendar yet";c.body=place==null?"Choose a weather location":"Calendar unavailable";c.url=calendarUrl;c.foot=place==null?"Based on your location":place.city+" · Office Holidays";
                StringBuilder info=new StringBuilder();ZoneId zone=place==null?ZoneId.systemDefault():place.timeZone();LocalDate today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
                for(Holiday h:holidays){LocalDate date;try{date=LocalDate.parse(h.date);}catch(Exception e){continue;}if(date.isBefore(today))continue;
                    if(c.target==0){c.title=c.metric=h.title;c.body=date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM",Locale.ENGLISH));long days=ChronoUnit.DAYS.between(today,date);c.foot=(days==0?"Today":days==1?"Tomorrow":"In "+days+" days")+" · "+(place==null?"Local calendar":place.city);c.target=date.atStartOfDay(zone).toInstant().toEpochMilli();c.url=h.url;}
                    if(info.length()<3500)info.append(date.format(DateTimeFormatter.ofPattern("d MMM uuuu",Locale.ENGLISH))).append(" · ").append(h.title).append('\n');
                }
                c.detail=(place==null?"":place.city+", "+Region.name(place.country)+"\n")+info+"\nCalendar: Office Holidays. Regional and religious dates can change.\n"+holidayError;add(row,c);
            }
            row.count=row.items.size();return row;
        }
        private static void add(Channels.Channel row,Card c){Trending.Pick p=new Trending.Pick();p.id=c.kind.hashCode();p.kind="insight";p.tab=KEY;p.title=c.title;p.meta=c.foot;p.overview=c.metric+"\n"+c.body+"\n"+c.detail;p.extra=c;row.items.add(p);}
        JSONObject json()throws Exception{
            JSONObject out=new JSONObject().put("location_key",locationKey).put("place",place==null?JSONObject.NULL:place.json()).put("air",air==null?JSONObject.NULL:air.json()).put("air_error",airError).put("holiday_error",holidayError).put("calendar_url",calendarUrl).put("sources",new JSONObject(sources));JSONArray a=new JSONArray(),h=new JSONArray();for(Story s:stories)a.put(s.json());for(Holiday x:holidays)h.put(x.json());return out.put("stories",a).put("holidays",h);
        }
        static State parse(JSONObject o){State s=new State();s.locationKey=o.optString("location_key");s.place=Place.parse(o.optJSONObject("place"));s.air=Air.parse(o.optJSONObject("air"));s.airError=o.optString("air_error");s.holidayError=o.optString("holiday_error");s.calendarUrl=o.optString("calendar_url");JSONArray a=o.optJSONArray("stories"),h=o.optJSONArray("holidays");for(int i=0;a!=null&&i<a.length();i++)s.stories.add(Story.parse(a.optJSONObject(i)));for(int i=0;h!=null&&i<h.length();i++)s.holidays.add(Holiday.parse(h.optJSONObject(i)));JSONObject sources=o.optJSONObject("sources");if(sources!=null)for(Iterator<String> i=sources.keys();i.hasNext();){String key=i.next();s.sources.put(key,sources.optString(key));}return s;}
    }
    interface Listener{void ready(State state);}
    private static final String[][] FEEDS={
        {"WSJ Tech","https://feeds.content.dowjones.io/public/rss/RSSWSJD","Tech","wsj.com"},
        {"WSJ Markets","https://feeds.content.dowjones.io/public/rss/RSSMarketsMain","Markets","wsj.com"},
        {"CNN","https://rss.cnn.com/rss/cnn_latest.rss","Tech","cnn.com"},
        {"CNBC Tech","https://www.cnbc.com/id/19854910/device/rss/rss.html","Tech","cnbc.com"},
        {"CNBC Markets","https://www.cnbc.com/id/100003114/device/rss/rss.html","Markets","cnbc.com"},
        {"TechCrunch AI","https://techcrunch.com/category/artificial-intelligence/feed/","AI","techcrunch.com"}};
    private final Context ctx;private final Weather weather;private final android.content.SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(),net=Executors.newFixedThreadPool(4);
    private final Handler main=new Handler(Looper.getMainLooper());private boolean running;private volatile boolean closed;
    HomeInsights(Context c,Weather weather){ctx=c.getApplicationContext();this.weather=weather;prefs=c.getSharedPreferences("home_insights",0);}
    State cached(){try{State s=State.parse(new JSONObject(prefs.getString("snapshot","{}")));if(!weather.widgetLocationKey().equals(s.locationKey)){s.place=null;s.air=null;s.holidays.clear();s.calendarUrl="";}return s;}catch(Exception e){return State.empty();}}
    static boolean sourceEnabled(Context ctx,String source){String key=source.startsWith("WSJ")?"wsj":source.startsWith("CNBC")?"cnbc":source.equals("CNN")?"cnn":"techcrunch";return HomeOptions.prefs(ctx).getBoolean("insight_source_"+key,true);}
    void refresh(Listener listener){
        if(running||closed||!HomeOptions.prefs(ctx).getBoolean("insights",true))return;long now=System.currentTimeMillis();String key=weather.widgetLocationKey();
        if(key.equals(prefs.getString("attempt_location",""))&&now-prefs.getLong("attempt",0)<15*60_000L)return;
        running=true;prefs.edit().putLong("attempt",now).putString("attempt_location",key).apply();
        worker.execute(()->{State state=cached();state.locationKey=key;long started=System.currentTimeMillis();List<Future<?>> jobs=new ArrayList<>();
            try{Place place=(HomeOptions.prefs(ctx).getBoolean("insight_air",true)||HomeOptions.prefs(ctx).getBoolean("insight_festivals",true))?weather.widgetPlace():null;if(place!=null&&(state.place==null||!state.place.key().equals(place.key()))){state.air=null;state.holidays.clear();}state.place=place;}catch(Exception e){state.airError="Location unavailable; choose a city in settings";state.place=null;state.air=null;state.holidays.clear();}
            Place place=state.place;
            if(place!=null&&HomeOptions.prefs(ctx).getBoolean("insight_air",true))jobs.add(net.submit(()->{try{state.air=parseAir(new JSONObject(get(String.format(Locale.US,"https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.2f&longitude=%.2f&current=us_aqi,pm2_5&timezone=auto",place.lat,place.lon),256000)),place.timeZone());state.airError="";}catch(Exception e){state.airError="Estimate service unavailable; last valid estimate is labelled cached";}}));
            if(place!=null&&HomeOptions.prefs(ctx).getBoolean("insight_festivals",true)&&(state.holidays.isEmpty()||started-prefs.getLong("holidays_at",0)>48*HOUR))jobs.add(net.submit(()->{try{
                String country=countrySlug(place.country),region=slug(place.region);String url="https://www.officeholidays.com/ics/"+country;String ics;
                if(!region.isEmpty()&&Arrays.asList("IN","US","CA","AU").contains(place.country)){try{ics=get(url+"/"+region,1000000);url+="/"+region;}catch(Exception ignored){ics=get(url,1000000);}}else ics=get(url,1000000);
                List<Holiday> holidays=parseHolidays(ics,Instant.ofEpochMilli(started).atZone(place.timeZone()).toLocalDate(),place.region);if(holidays.isEmpty())throw new IOException();state.holidays=holidays;state.calendarUrl=url;state.holidayError="";prefs.edit().putLong("holidays_at",started).apply();
            }catch(Exception e){state.holidayError="Regional calendar could not be refreshed";}}));
            Map<String,List<Story>> feeds=new ConcurrentHashMap<>();Map<String,String> statuses=new ConcurrentHashMap<>();
            if(HomeOptions.prefs(ctx).getBoolean("insight_news",true))for(String[] feed:FEEDS)if(sourceEnabled(ctx,feed[0]))jobs.add(net.submit(()->{try{
                List<Story> articles=parseFeed(get(feed[1],1000000),feed[0],feed[2],feed[3],started);if(articles.isEmpty())throw new IOException();feeds.put(feed[0],articles);statuses.put(feed[0],"Updated "+time(started));
            }catch(Exception e){List<Story> saved=new ArrayList<>();for(Story s:state.stories)if(s.source.equals(feed[0])&&freshStory(s,started))saved.add(s);feeds.put(feed[0],saved);statuses.put(feed[0],saved.isEmpty()?"Unavailable":"Unavailable · showing dated cached headlines");}}));
            for(Future<?> job:jobs)try{job.get();}catch(Exception ignored){}
            if(!feeds.isEmpty()){List<Story> merged=new ArrayList<>();for(String[] f:FEEDS)merged.addAll(feeds.getOrDefault(f[0],Collections.emptyList()));state.stories=mergeStories(merged,started);state.sources.clear();for(String[] f:FEEDS)if(statuses.containsKey(f[0]))state.sources.put(f[0],statuses.get(f[0]));}
            try{prefs.edit().putString("snapshot",state.json().toString()).apply();}catch(Exception ignored){}
            main.post(()->{running=false;if(!closed&&key.equals(weather.widgetLocationKey()))listener.ready(state);});
        });
    }
    void invalidate(){prefs.edit().remove("attempt").apply();}
    void close(){closed=true;worker.shutdownNow();net.shutdownNow();main.removeCallbacksAndMessages(null);}
    static Air parseAir(JSONObject o,ZoneId fallback)throws Exception{
        JSONObject cur=o.getJSONObject("current");double number=cur.optDouble("us_aqi",Double.NaN);if(!Double.isFinite(number)||number<0||number>1000)throw new IOException("Invalid AQI");ZoneId zone;try{zone=ZoneId.of(o.optString("timezone"));}catch(Exception e){zone=fallback;}
        return new Air((int)Math.round(number),cur.optDouble("pm2_5",Double.NaN),LocalDateTime.parse(cur.getString("time")).atZone(zone).toInstant().toEpochMilli());
    }
    static String countrySlug(String cc){Map<String,String> known=new HashMap<>();known.put("US","usa");known.put("GB","united-kingdom");known.put("AE","uae");known.put("KR","south-korea");known.put("CZ","czech-republic");known.put("CI","ivory-coast");known.put("HK","hong-kong");known.put("MM","myanmar");return known.getOrDefault(cc,slug(new Locale("",cc).getDisplayCountry(Locale.ENGLISH)));}
    static String slug(String text){return java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceFirst("^(state of |national capital territory of )","").replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");}
    static List<Holiday> parseHolidays(String ics,LocalDate today,String region){
        List<Holiday> out=new ArrayList<>();Set<String> seen=new HashSet<>();Map<String,String> event=null;
        for(String line:ics.replace("\r\n ","").replace("\r\n\t","").split("\\r?\\n")){
            if(line.equals("BEGIN:VEVENT")){event=new HashMap<>();continue;}if(event==null)continue;
            if(line.equals("END:VEVENT")){try{
                LocalDate date=LocalDate.parse(event.getOrDefault("DTSTART",""),DateTimeFormatter.BASIC_ISO_DATE);String title=event.getOrDefault("SUMMARY","").replaceFirst("^[^:]{1,45}:\\s*","").replaceAll("\\s*\\((National|Regional|Not A Public|Public) Holiday\\)","").trim();String location=event.getOrDefault("LOCATION","");
                boolean local=!location.contains(":")||location.toLowerCase(Locale.ROOT).matches(".*(all states|national|countrywide).*")||!region.isEmpty()&&slug(location).contains(slug(region));
                if(local&&!date.isBefore(today)&&date.isBefore(today.plusDays(400))&&!title.isEmpty()&&!title.toLowerCase(Locale.ROOT).matches(".*(bank holiday|bank's holiday|accounts|election|working day).*")&&seen.add(date+"|"+title))out.add(new Holiday(title,safeUrl(event.getOrDefault("URL",""),"officeholidays.com"),date.toString()));
            }catch(Exception ignored){}event=null;continue;}
            int colon=line.indexOf(':');if(colon>0){String key=line.substring(0,colon).split(";",2)[0];event.put(key,line.substring(colon+1).replace("\\,",",").replace("\\n","\n"));}
        }
        out.sort(Comparator.comparing(h->h.date));return new ArrayList<>(out.subList(0,Math.min(12,out.size())));
    }
    static boolean freshStory(Story story,long now){return story.published>0&&story.published<=now+15*60_000L&&now-story.published<=7*24*HOUR&&!story.url.isEmpty();}
    static List<Story> mergeStories(List<Story> input,long now){List<Story> sorted=new ArrayList<>(input);sorted.sort((a,b)->Long.compare(b.published,a.published));Set<String> seen=new HashSet<>();List<Story> result=new ArrayList<>();for(Story s:sorted)if(freshStory(s,now)&&eligibleStory(s)&&seen.add(s.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","")))result.add(s);return new ArrayList<>(result.subList(0,Math.min(32,result.size())));}
    static List<Story> parseFeed(String xml,String source,String fallbackTopic,String host,long now)throws Exception{
        if(xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException("Unsupported XML declaration");
        XmlPullParser parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(new StringReader(xml));List<Story> result=new ArrayList<>();Map<String,String> item=null;String field=null;
        for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next()){
            String tag=event==XmlPullParser.START_TAG||event==XmlPullParser.END_TAG?parser.getName():"";
            if(event==XmlPullParser.START_TAG){if(tag.equals("item")||tag.equals("entry"))item=new HashMap<>();else if(item!=null&&Arrays.asList("title","link","pubDate","published","updated").contains(tag)){field=tag;if(tag.equals("link")&&parser.getAttributeValue(null,"href")!=null)item.put("link",parser.getAttributeValue(null,"href"));}}
            else if((event==XmlPullParser.TEXT||event==XmlPullParser.CDSECT)&&item!=null&&field!=null)item.put(field,item.getOrDefault(field,"")+parser.getText());
            else if(event==XmlPullParser.END_TAG){if(tag.equals("item")||tag.equals("entry")){String title=item.getOrDefault("title","").replaceAll("<[^>]*>","").replaceAll("\\s+"," ").trim();String url=safeUrl(item.getOrDefault("link","").trim(),host);long published=date(item.getOrDefault("pubDate",item.getOrDefault("published",item.getOrDefault("updated",""))));String topic=title.toLowerCase(Locale.ROOT).matches(".*(\\bai\\b|artificial intelligence|openai|anthropic|chatgpt|deepmind|llm).*" )?"AI":fallbackTopic;Story story=new Story(title,url,source,topic,published);if(title.length()>=8&&eligibleStory(story)&&freshStory(story,now))result.add(story);item=null;}field=null;}
        }
        return mergeStories(result,now);
    }
    private static final String COMPANIES="apple|microsoft|google|alphabet|amazon|\\bmeta\\b|openai|anthropic|chatgpt|nvidia|\\bamd\\b|intel|tesla|oracle|\\bibm\\b|palantir|qualcomm|salesforce|broadcom|spacex";
    static boolean usFocused(String title){
        String lower=title.toLowerCase(Locale.ROOT);
        return title.matches("(?s).*\\bUS\\b.*")||lower.matches(".*(u\\.s\\.|united states|wall street|nasdaq|s&p|dow jones|federal reserve|\\bfed\\b|american|"+COMPANIES+"|silicon valley|silicon-valley|blackrock|blackstone|jpmorgan|goldman sachs|morgan stanley|nyse).*" );
    }
    static boolean eligibleStory(Story story){
        String lower=story.title.toLowerCase(Locale.ROOT);
        boolean ai=lower.matches(".*(\\bai\\b|artificial intelligence|openai|anthropic|chatgpt|deepmind|\\bllm\\b).*" );
        boolean relevant="AI".equals(story.topic)?ai:"Markets".equals(story.topic)?lower.matches(".*(stock|market|wall street|nasdaq|s&p|dow|bond|fund|invest|earnings|profit|revenue|bank|fed|inflation|trading|financ|econom|commodit|futures|oil|natural gas).*" )
                :ai||lower.matches(".*(tech|chip|software|cyber|data.center|semiconductor|smartphone|internet|robot|satellite|"+COMPANIES+").*" );
        return relevant&&(usFocused(story.title)||ai&&story.source.startsWith("WSJ"));
    }
    static long date(String text){try{return ZonedDateTime.parse(text,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception e){try{return OffsetDateTime.parse(text).toInstant().toEpochMilli();}catch(Exception ignored){return 0;}}}
    static String safeUrl(String text,String host){try{URI uri=new URI(text);String h=uri.getHost();return "https".equals(uri.getScheme())&&h!=null&&(h.equals(host)||h.endsWith("."+host))&&uri.getUserInfo()==null?uri.toString():"";}catch(Exception e){return "";}}
    static String time(long epoch){return Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm",Locale.ENGLISH));}
    static String get(String url,int limit)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setRequestProperty("User-Agent","SweepTV/1.0 (personal RSS reader)");
        try{if(c.getResponseCode()!=200)throw new IOException("Source unavailable");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))>0;){if(out.size()+n>limit)throw new IOException("Response too large");out.write(buffer,0,n);}return out.toString("UTF-8");}}finally{c.disconnect();}
    }
}
