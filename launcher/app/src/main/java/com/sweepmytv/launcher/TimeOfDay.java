package com.sweepmytv.launcher;

/** Three-stop sky gradients following solar events and hourly cloud/rain conditions. */
final class TimeOfDay {
    static final String PREF="background_by_time";
    // Original artistic sky colours, anchored to the day's solar events rather than a light/dark switch.
    private static final int[][] STOPS={
        {0xFF111A2E,0xFF202B46,0xFF30364F,0xFFB8B3DD},
        {0xFF172039,0xFF2E304C,0xFF493A54,0xFFC7A8CD},
        {0xFF202941,0xFF3D354E,0xFF5A4052,0xFFD4A5BC},
        {0xFF392637,0xFF5E3839,0xFF784E35,0xFFE0AA83},
        {0xFF3C2D1D,0xFF654722,0xFF765825,0xFFE0BF7E},
        {0xFF402F18,0xFF684C1D,0xFF785B23,0xFFE1C177},
        {0xFF3D2F17,0xFF624A1B,0xFF79591F,0xFFE4C780},
        {0xFF462D18,0xFF72491F,0xFF885A29,0xFFE5BC77},
        {0xFF502C18,0xFF7A401A,0xFF915225,0xFFE9AC6B},
        {0xFF442135,0xFF6A3230,0xFF7D431F,0xFFE4A17B},
        {0xFF3D2331,0xFF633139,0xFF784333,0xFFD9A08E},
        {0xFF1C2740,0xFF30334F,0xFF41415D,0xFFBFAEDB},
        {0xFF14213A,0xFF24304B,0xFF343D59,0xFFADBAE0},
        {0xFF0B1427,0xFF14223B,0xFF202D47,0xFFA7B8DF},
        {0xFF111A2E,0xFF202B46,0xFF30364F,0xFFB8B3DD}};
    private static final String[] PHASES={"Twilight","Twilight","Dawn","Sunrise","Morning","Late morning","Noon warmth","Afternoon","Late afternoon","Evening","Sunset","Dusk","Blue hour","Night","Twilight"};
    static final class Palette {
        final int top,mid,bottom,accent,text,muted;final boolean light;final String phase;
        Palette(int top,int mid,int bottom,int accent,String phase) {
            // Every solar phase stays dark enough for light text; daytime lifts colour, not a white veil.
            this.top=top;this.mid=mid;this.bottom=bottom;this.phase=phase;light=false;
            text=readable(0xFFEDF2FA,top,mid,bottom,0xFFFFFFFF,4.5);
            muted=readable(0xFFB9C3D6,top,mid,bottom,text,4.5);
            this.accent=readable(accent,top,mid,bottom,text,3);
        }
    }
    private final java.time.Clock clock;
    TimeOfDay() {this(java.time.Clock.systemUTC());}
    TimeOfDay(java.time.Clock clock) {this.clock=clock;}
    int minuteNow() {
        // Read the current zone each time: a timezone change must take effect without restarting the launcher.
        java.time.ZonedDateTime now=java.time.ZonedDateTime.ofInstant(clock.instant(),java.time.ZoneId.systemDefault());
        return now.getHour()*60+now.getMinute();
    }
    long nowMillis(){return clock.instant().toEpochMilli();}
    static Palette palette(int minuteOfDay) {return cycle(Math.floorMod(minuteOfDay,1440)-360,720,0,0,0,false);}
    static Palette palette(long now,SkyWeather.Sample sky) {
        return cycle((now-sky.sunrise)/60_000.0,(sky.sunset-sky.sunrise)/60_000.0,sky.clouds,sky.rain,sky.code,sky.weatherKnown);
    }
    private static Palette cycle(double position,double daylight,double clouds,double rain,int code,boolean weather) {
        daylight=SkyWeather.clamp(daylight,180,1320);double night=1440-daylight,lead=Math.min(120,night*.22);
        double[] times={-lead,-lead*.625,-lead*.25,0,daylight*.15,daylight*.32,daylight*.5,
                daylight*.66,daylight*.78,daylight*.9,daylight,daylight+Math.min(45,night*.12),
                daylight+Math.min(100,night*.27),daylight+Math.min(180,night*.5),1440-lead};
        while(position<times[0])position+=1440;while(position>=times[times.length-1])position-=1440;
        int at=0;while(at<times.length-2&&position>=times[at+1])at++;
        float f=(float)((position-times[at])/(times[at+1]-times[at]));f=f*f*(3-2*f);
        int top=blend(STOPS[at][0],STOPS[at+1][0],f),mid=blend(STOPS[at][1],STOPS[at+1][1],f),bottom=blend(STOPS[at][2],STOPS[at+1][2],f);
        int accent=blend(STOPS[at][3],STOPS[at+1][3],f);
        if(weather) {
            boolean sunUp=position>=0&&position<daylight;
            float cloud=(float)SkyWeather.clamp(clouds/100*.4,0,.4);
            boolean wet=code>=51&&code<=86||code>=95;
            float damp=(float)Math.min(.2,rain/12)+(wet?.08f:0)+(code>=95?.12f:0);
            float fog=code==45||code==48?.32f:0;
            top=blend(top,sunUp?0xFF3E3B39:0xFF1C2B44,Math.min(.65f,cloud+damp+fog));
            mid=blend(mid,sunUp?0xFF504A41:0xFF28354C,Math.min(.65f,cloud+damp+fog));
            bottom=blend(bottom,sunUp?0xFF5F5446:0xFF354059,Math.min(.65f,cloud+damp+fog));
            accent=blend(accent,sunUp?0xFFC7B18A:0xFFAAB9D6,Math.min(.35f,cloud+damp));
        }
        return new Palette(top,mid,bottom,accent,PHASES[at]);
    }
    static int[] colors(int minuteOfDay) {Palette p=palette(minuteOfDay);return new int[]{p.top,p.bottom};}
    static int blend(int a,int b,float f) {
        int color=0xFF000000;
        for(int shift=0;shift<=16;shift+=8)color|=Math.round(((a>>>shift)&255)*(1-f)+((b>>>shift)&255)*f)<<shift;
        return color;
    }
    static double luminance(int color) {
        double sum=0;double[] weights={.0722,.7152,.2126};
        for(int channel=0;channel<3;channel++) {
            double v=((color>>>(channel*8))&255)/255.0;
            sum+=weights[channel]*(v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));
        }
        return sum;
    }
    static double contrast(int a,int b) {double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static double minContrast(int color,int top,int mid,int bottom) {return Math.min(contrast(color,mid),Math.min(contrast(color,top),contrast(color,bottom)));}
    private static int readable(int color,int top,int mid,int bottom,int fallback,double ratio) {return minContrast(color,top,mid,bottom)>=ratio?color:fallback;}
    static int foreground(int color) {return contrast(0xFF000000,color)>contrast(0xFFFFFFFF,color)?0xFF000000:0xFFFFFFFF;}
}
