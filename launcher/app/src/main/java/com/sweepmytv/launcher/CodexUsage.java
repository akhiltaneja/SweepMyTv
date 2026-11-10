package com.sweepmytv.launcher;

import android.content.Context;
import org.json.JSONObject;
import java.util.Locale;

/** Sanitised snapshot from the optional Mac companion. Never contains account credentials. */
final class CodexUsage {
    static final String FILE="codex_usage.json";
    final long syncedAt,resetsAt;final double usedPercent;final int windowMinutes;
    private CodexUsage(long syncedAt,double used,long reset,int window){this.syncedAt=syncedAt;usedPercent=used;resetsAt=reset;windowMinutes=window;}
    static CodexUsage parse(JSONObject o,long now){
        long at=o.optLong("synced_at",0),reset=o.optLong("resets_at",0);double used=o.optDouble("used_percent",Double.NaN);int minutes=o.optInt("window_minutes",0);
        if(at<=0||at>now+300_000||!Double.isFinite(used)||used<0||used>100||minutes<=0)return null;
        return new CodexUsage(at,used,reset,minutes);
    }
    static CodexUsage read(Context c){try{String text=Keys.read(c,FILE);return text==null?null:parse(new JSONObject(text),System.currentTimeMillis());}catch(Exception e){return null;}}
    boolean fresh(long now){return now>=syncedAt-300_000&&now-syncedAt<=15*60_000L&&(resetsAt<=0||now<resetsAt);}
    String window(){return windowMinutes>=10080?"week":windowMinutes>=60?(windowMinutes/60)+"h":windowMinutes+"m";}
    String compact(long now){return fresh(now)?window()+" "+Math.round(100-usedPercent)+"% left":"Sync needed";}
    String detail(long now){return String.format(Locale.getDefault(),"%d%% used · %d min window · synced %d min ago%s",Math.round(usedPercent),windowMinutes,Math.max(0,(now-syncedAt)/60_000),fresh(now)?"":" · stale");}
}
