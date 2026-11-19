package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Timestamped market quotes and a shared USD/INR reference rate. */
final class MarketQuotes implements AutoCloseable {
    private static final long MINUTE = 60_000L;
    private static final long WEEK = 7 * 24 * 60 * MINUTE;
    private static final String FX_URL = "https://api.frankfurter.dev/v1/latest?base=USD&symbols=INR";
    enum Group {
        CRYPTO("Crypto"), METALS("Metals"), MAG7("Mag 7"),
        INDIA("India"), INDICES("Indices");

        final String title;
        Group(String title) { this.title = title; }
    }
    enum Feed { COINBASE, METAL, NASDAQ, CHART }
    enum Unit { USD, INR, POINTS }

    enum Asset {
        BTC(Group.CRYPTO, "BTC", "Bitcoin", 0xFFF7931A, R.drawable.market_btc, Feed.COINBASE, Unit.USD, true),
        ETH(Group.CRYPTO, "ETH", "Ethereum", 0xFF9CA7EB, R.drawable.market_eth, Feed.COINBASE, Unit.USD, false),
        NVDA(Group.MAG7, "NVDA", "NVIDIA", 0xFF76B900, R.drawable.market_nvda, Feed.NASDAQ, Unit.USD, true),
        GOLD(Group.METALS, "XAU", "Gold", 0xFFE7BC58, R.drawable.market_gold, Feed.METAL, Unit.USD, true),
        SILVER(Group.METALS, "XAG", "Silver", 0xFFB5C3D7, R.drawable.market_silver, Feed.METAL, Unit.USD, false),
        AAPL(Group.MAG7, "AAPL", "Apple", 0xFFD0D6DF, R.drawable.market_aapl),
        MSFT(Group.MAG7, "MSFT", "Microsoft", 0xFF54AAE0, R.drawable.market_msft),
        GOOGL(Group.MAG7, "GOOGL", "Alphabet", 0xFF77A5FF, R.drawable.market_googl),
        AMZN(Group.MAG7, "AMZN", "Amazon", 0xFFFFA445, R.drawable.market_amzn),
        META(Group.MAG7, "META", "Meta", 0xFF638FFF, R.drawable.market_meta),
        TSLA(Group.MAG7, "TSLA", "Tesla", 0xFFF96F75, R.drawable.market_tsla),
        RELIANCE(Group.INDIA, "RELIANCE.NS", "Reliance", 0xFFDBB478, R.drawable.market_reliance),
        HDFCBANK(Group.INDIA, "HDFCBANK.NS", "HDFC Bank", 0xFF6597EC, R.drawable.market_hdfcbank),
        ICICIBANK(Group.INDIA, "ICICIBANK.NS", "ICICI Bank", 0xFFFF895E, R.drawable.market_icicibank),
        AIRTEL(Group.INDIA, "BHARTIARTL.NS", "Airtel", 0xFFFF6971, R.drawable.market_airtel),
        SBIN(Group.INDIA, "SBIN.NS", "SBI", 0xFF4EC7EF, R.drawable.market_sbin),
        NIFTY(Group.INDICES, "^NSEI", "Nifty 50", 0xFFFFB758, R.drawable.market_nifty),
        BANKNIFTY(Group.INDICES, "^NSEBANK", "Nifty Bank", 0xFF81AFEF, R.drawable.market_banknifty),
        SENSEX(Group.INDICES, "^BSESN", "Sensex", 0xFF76BDF1, R.drawable.market_sensex),
        SP500(Group.INDICES, "^GSPC", "S&P 500", 0xFF8EB9EE, R.drawable.market_sp500),
        NDX(Group.INDICES, "^NDX", "Nasdaq 100", 0xFF68C7C6, R.drawable.market_ndx);

        final Group group;
        final Feed feed;
        final Unit nativeUnit;
        final String key, title, symbol, source, url;
        final int color, icon, intervalMinutes;
        final long maxAge;
        final boolean defaultEnabled;

        Asset(Group group, String symbol, String title, int color, int icon) {
            this(group, symbol, title, color, icon, group == Group.MAG7 ? Feed.NASDAQ : Feed.CHART,
                    group == Group.MAG7 ? Unit.USD : group == Group.INDIA ? Unit.INR : Unit.POINTS, false);
        }

        Asset(Group group, String symbol, String title, int color, int icon, Feed feed, Unit unit, boolean enabled) {
            this.group = group;
            this.feed = feed;
            this.nativeUnit = unit;
            this.symbol = symbol;
            this.title = title;
            this.color = color;
            this.icon = icon;
            this.defaultEnabled = enabled;
            key = "market_" + name().toLowerCase(Locale.ROOT);
            switch (feed) {
                case COINBASE:
                    source = "Coinbase";
                    url = "https://api.exchange.coinbase.com/products/" + symbol + "-USD/ticker";
                    intervalMinutes = 3;
                    maxAge = 30 * MINUTE;
                    break;
                case METAL:
                    source = "Gold API";
                    url = "https://api.gold-api.com/price/" + symbol;
                    intervalMinutes = 5;
                    maxAge = 4 * 24 * 60 * MINUTE;
                    break;
                case NASDAQ:
                    source = "Nasdaq";
                    url = "https://api.nasdaq.com/api/quote/" + symbol + "/info?assetclass=stocks";
                    intervalMinutes = 15;
                    maxAge = WEEK;
                    break;
                default:
                    source = "Yahoo Finance";
                    url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                            + symbol.replace("^", "%5E") + "?range=5d&interval=1d";
                    intervalMinutes = 20;
                    maxAge = WEEK;
                    break;
            }
        }
    }

    static final class Quote {
        final double price;
        final long at;
        final String session;

        Quote(double price,long at,String session) { this.price=price;this.at=at;this.session=session; }

        boolean valid(Asset asset,long now) {
            return Double.isFinite(price)&&price>0&&at>0&&at<=now+2*MINUTE&&now-at<=asset.maxAge;
        }
    }

    private final SharedPreferences store;
    private final SharedPreferences options;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final EnumSet<Asset> loading = EnumSet.noneOf(Asset.class);
    private final Map<Asset,Long> attempts = new EnumMap<>(Asset.class);
    private final Map<Asset,Quote> quotes = new EnumMap<>(Asset.class);
    private boolean closed;
    private long fxAttempt;
    private Fx fx;

    private static final class Fx {
        final double inr;
        final java.time.LocalDate date;
        Fx(double inr, java.time.LocalDate date) { this.inr = inr; this.date = date; }

        boolean valid() {
            java.time.LocalDate now = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            return Double.isFinite(inr) && inr > 0 && !date.isAfter(now) && !date.isBefore(now.minusDays(7));
        }
    }

    private static Fx parseFx(JSONObject response) throws Exception {
        if (!"USD".equals(response.getString("base"))) throw new IllegalArgumentException("Unexpected FX base");
        return new Fx(response.getJSONObject("rates").getDouble("INR"),
                java.time.LocalDate.parse(response.getString("date")));
    }

    MarketQuotes(Context context) {
        store=context.getSharedPreferences("market_quotes",Context.MODE_PRIVATE);
        options=HomeOptions.prefs(context);
        try { fx = parseFx(new JSONObject(store.getString("usd_inr", "{}"))); } catch (Exception ignored) { }
        for (Asset asset:Asset.values()) {
            try {
                JSONObject saved=new JSONObject(store.getString(asset.key,"{}"));
                quotes.put(asset,new Quote(saved.optDouble("price"),saved.optLong("at"),saved.optString("session")));
            } catch(Exception ignored) { }
        }
    }

    List<Asset> enabled() {
        List<Asset> result=new ArrayList<>();
        for (Asset asset:Asset.values()) if(isEnabled(asset)) result.add(asset);
        return result;
    }

    boolean isEnabled(Asset asset) {
        return options.getBoolean(asset.key, asset.defaultEnabled);
    }

    void setEnabled(Asset asset, boolean enabled) {
        options.edit().putBoolean(asset.key, enabled).apply();
    }

    Unit displayUnit(Asset asset) {
        if (asset.nativeUnit == Unit.POINTS) return Unit.POINTS;
        String saved = options.getString(asset.key + "_currency", asset.nativeUnit.name());
        return "INR".equals(saved) ? Unit.INR : Unit.USD;
    }

    void flipCurrency(Asset asset) {
        if (asset.nativeUnit == Unit.POINTS) return;
        Unit other = displayUnit(asset) == Unit.INR ? Unit.USD : Unit.INR;
        options.edit().putString(asset.key + "_currency", other.name()).apply();
    }

    static double convert(double price, Unit nativeUnit, Unit displayUnit, double inrPerUsd) {
        if (nativeUnit == displayUnit) return price;
        if (!Double.isFinite(inrPerUsd) || inrPerUsd <= 0) return Double.NaN;
        return displayUnit == Unit.INR ? price * inrPerUsd : price / inrPerUsd;
    }

    double displayPrice(Asset asset, Quote quote) {
        return quote == null ? Double.NaN : convert(quote.price, asset.nativeUnit, displayUnit(asset),
                fx != null && fx.valid() ? fx.inr : Double.NaN);
    }

    boolean needsFx(Asset asset) { return displayUnit(asset) != asset.nativeUnit; }

    Quote quote(Asset asset) {
        Quote value=quotes.get(asset);
        return value!=null&&value.valid(asset,System.currentTimeMillis())?value:null;
    }

    void refresh(Runnable updated) {
        if (closed) return;
        long now=System.currentTimeMillis();
        List<Asset> assets = enabled();
        if (assets.stream().anyMatch(this::needsFx) && now - fxAttempt >= 60 * MINUTE) {
            fxAttempt = now;
            worker.execute(() -> {
                try {
                    String body = HomeInsights.get(FX_URL, 16_000);
                    Fx rate = parseFx(new JSONObject(body));
                    if (!rate.valid()) return;
                    main.post(() -> {
                        if (closed || fx != null && rate.date.isBefore(fx.date)) return;
                        fx = rate;
                        store.edit().putString("usd_inr", body).apply();
                        updated.run();
                    });
                } catch (Exception ignored) { }
            });
        }
        for (Asset asset:assets) {
            if (loading.contains(asset)||now-attempts.getOrDefault(asset,0L)<asset.intervalMinutes*MINUTE) continue;
            attempts.put(asset,now);
            loading.add(asset);
            worker.execute(()->{
                Quote value=null;
                try {
                    String body=HomeInsights.get(asset.url,128000,asset.feed == Feed.NASDAQ || asset.feed == Feed.CHART ? "Mozilla/5.0" : "SweepTV/1.0");
                    Quote candidate=parse(asset,new JSONObject(body));
                    if(candidate.valid(asset,System.currentTimeMillis())) value=candidate;
                } catch(Exception ignored) { }
                Quote result=value;
                main.post(()->{
                    if(closed)return;
                    loading.remove(asset);
                    Quote previous=quotes.get(asset);
                    if(result!=null&&(previous==null||result.at>=previous.at)) {
                        quotes.put(asset,result);
                        try {
                            store.edit().putString(asset.key,new JSONObject().put("price",result.price)
                                    .put("at",result.at).put("session",result.session).toString()).apply();
                        } catch(Exception ignored) { }
                    }
                    updated.run();
                });
            });
        }
    }

    static Quote parse(Asset asset,JSONObject json) throws Exception {
        if (asset.feed == Feed.COINBASE) return new Quote(json.getDouble("price"),Instant.parse(json.getString("time")).toEpochMilli(),"");
        if (asset.feed == Feed.METAL) {
            if(!"USD".equals(json.getString("currency"))||!asset.symbol.equals(json.getString("symbol")))
                throw new IllegalArgumentException("Unexpected metal currency or symbol");
            return new Quote(json.getDouble("price"),Instant.parse(json.getString("updatedAt")).toEpochMilli(),"");
        }
        if (asset.feed == Feed.CHART) {
            JSONObject data = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
            boolean points = asset.nativeUnit == Unit.POINTS;
            if (!asset.symbol.equals(data.getString("symbol"))
                    || (points ? !"INDEX".equals(data.getString("instrumentType"))
                    : !"EQUITY".equals(data.getString("instrumentType")) || !asset.nativeUnit.name().equals(data.getString("currency")))) {
                throw new IllegalArgumentException("Unexpected chart symbol, type or currency");
            }
            return new Quote(data.getDouble("regularMarketPrice"), data.getLong("regularMarketTime") * 1000, "Last quote");
        }
        JSONObject data=json.getJSONObject("data");
        if(!asset.symbol.equals(data.getString("symbol"))) throw new IllegalArgumentException("Unexpected Nasdaq symbol");
        String market=data.optString("marketStatus");
        Quote primary=nasdaqTrade(data.optJSONObject("primaryData"),market);
        Quote secondary=nasdaqTrade(data.optJSONObject("secondaryData"),"Closed");
        if(primary==null) {
            if(secondary!=null)return secondary;
            throw new IllegalArgumentException("No timestamped Nasdaq quote");
        }
        return secondary!=null&&secondary.at>primary.at?secondary:primary;
    }

    private static Quote nasdaqTrade(JSONObject trade,String market) {
        if(trade==null)return null;
        try {
            String raw=trade.getString("lastTradeTimestamp").trim().replaceAll("(?i)^closed at +","").replaceFirst(" ET$","");
            long at=LocalDateTime.parse(raw,DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a",Locale.US))
                    .atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
            double price=Double.parseDouble(trade.getString("lastSalePrice").replace("$","").replace(",","").trim());
            String session=market.toLowerCase(Locale.ROOT).replace('-',' ');
            String label=session.contains("after")?"After hours":session.contains("pre")?"Pre-market":
                    session.contains("closed")||!trade.optBoolean("isRealTime")?"Last trade":"Nasdaq";
            return new Quote(price,at,label);
        } catch(Exception ignored) { return null; }
    }

    @Override public void close() {
        closed=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);
    }
}
