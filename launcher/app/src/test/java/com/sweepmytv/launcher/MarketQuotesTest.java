package com.sweepmytv.launcher;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.Instant;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class MarketQuotesTest {
    @Test public void nasdaqPreservesTheTradeTimeAndChoosesTheNewerSession() throws Exception {
        JSONObject trade = new JSONObject().put("lastSalePrice","$167.50")
                .put("lastTradeTimestamp","Nov 12, 2026 6:28 PM ET").put("isRealTime",true);
        JSONObject close = new JSONObject().put("lastSalePrice","$166.19")
                .put("lastTradeTimestamp","Closed at Nov 12, 2026 4:00 PM ET");
        JSONObject data = new JSONObject().put("symbol","NVDA").put("marketStatus","After-Hours")
                .put("primaryData",trade).put("secondaryData",close);
        MarketQuotes.Quote quote = MarketQuotes.parse(MarketQuotes.Asset.NVDA,new JSONObject().put("data",data));
        assertEquals(167.50,quote.price,0);
        assertEquals(Instant.parse("2026-11-12T23:28:00Z").toEpochMilli(),quote.at);
        assertEquals("After hours",quote.session);
    }

    @Test public void quotesKeepTheirCurrencyAndExpireUsingObservationTime() throws Exception {
        String time="2026-11-12T23:28:00Z";
        MarketQuotes.Quote bitcoin=MarketQuotes.parse(MarketQuotes.Asset.BTC,new JSONObject().put("price","71246.74").put("time",time));
        assertEquals(71246.74,bitcoin.price,0);
        assertTrue(bitcoin.valid(MarketQuotes.Asset.BTC,bitcoin.at+60_000));
        assertFalse(bitcoin.valid(MarketQuotes.Asset.BTC,bitcoin.at+31*60_000));
        JSONObject gold=new JSONObject().put("currency","USD").put("symbol","XAU").put("price",3877.30).put("updatedAt",time);
        assertEquals(3877.30,MarketQuotes.parse(MarketQuotes.Asset.GOLD,gold).price,0);
        try {
            MarketQuotes.parse(MarketQuotes.Asset.GOLD,gold.put("currency","INR"));
            fail("A non-USD price must not be shown as USD");
        } catch(IllegalArgumentException expected) { }
    }
    @Test public void nativeCurrenciesAndIndexPointsAreKeptSeparate() throws Exception {
        try (MarketQuotes quotes = new MarketQuotes(org.robolectric.RuntimeEnvironment.getApplication())) {
            assertEquals(java.util.List.of(MarketQuotes.Asset.BTC, MarketQuotes.Asset.NVDA, MarketQuotes.Asset.GOLD), quotes.enabled());
            assertEquals(MarketQuotes.Unit.USD, quotes.displayUnit(MarketQuotes.Asset.SILVER));
            assertEquals(MarketQuotes.Unit.INR, quotes.displayUnit(MarketQuotes.Asset.RELIANCE));
            quotes.flipCurrency(MarketQuotes.Asset.RELIANCE);
            assertEquals(MarketQuotes.Unit.USD, quotes.displayUnit(MarketQuotes.Asset.RELIANCE));
            quotes.flipCurrency(MarketQuotes.Asset.NIFTY);
            assertEquals(MarketQuotes.Unit.POINTS, quotes.displayUnit(MarketQuotes.Asset.NIFTY));
        }
        assertEquals(940, MarketQuotes.convert(10, MarketQuotes.Unit.USD, MarketQuotes.Unit.INR, 94), 0);
        assertEquals(10, MarketQuotes.convert(940, MarketQuotes.Unit.INR, MarketQuotes.Unit.USD, 94), 0);
        assertTrue(Double.isNaN(MarketQuotes.convert(10, MarketQuotes.Unit.USD, MarketQuotes.Unit.INR, Double.NaN)));
    }

    @Test public void chartChecksTheInstrumentAndPreservesNativeInr() throws Exception {
        JSONObject meta = new JSONObject().put("symbol", "RELIANCE.NS").put("currency", "INR")
                .put("instrumentType", "EQUITY").put("regularMarketPrice", 1339.7).put("regularMarketTime", 1794563884L);
        JSONObject json = new JSONObject().put("chart", new JSONObject().put("result",
                new org.json.JSONArray().put(new JSONObject().put("meta", meta))));
        MarketQuotes.Quote quote = MarketQuotes.parse(MarketQuotes.Asset.RELIANCE, json);
        assertEquals(1339.7, quote.price, 0);
        assertEquals(1794563884000L, quote.at);
        meta.put("currency", "USD");
        try {
            MarketQuotes.parse(MarketQuotes.Asset.RELIANCE, json);
            fail("Reject prices with the wrong native currency");
        } catch (IllegalArgumentException expected) { }
    }
}
