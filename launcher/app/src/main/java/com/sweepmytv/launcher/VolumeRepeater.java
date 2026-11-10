package com.sweepmytv.launcher;

import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.function.IntConsumer;

/** Supplies repeats while the accessibility key filter suppresses firmware-generated volume repeats. */
final class VolumeRepeater {
    private static final long DELAY=400,INTERVAL=100,MAX_HOLD=15_000;
    private final Handler handler;private final IntConsumer adjust;
    private int key;private long started;
    private final Runnable repeat=new Runnable(){@Override public void run(){if(key==0)return;if(SystemClock.uptimeMillis()-started>=MAX_HOLD){stop();return;}adjust.accept(direction(key));handler.postDelayed(this,INTERVAL);}};
    VolumeRepeater(Handler handler,IntConsumer adjust){this.handler=handler;this.adjust=adjust;}
    private static int direction(int code){return code==KeyEvent.KEYCODE_VOLUME_UP?1:-1;}
    boolean handle(KeyEvent event){
        int code=event.getKeyCode();boolean volume=code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN;
        if(!volume){if(event.getAction()==KeyEvent.ACTION_DOWN)stop();return false;}
        if(event.getAction()==KeyEvent.ACTION_UP){if(key==code)stop();return true;}
        if(event.getAction()!=KeyEvent.ACTION_DOWN||event.isCanceled()){stop();return true;}
        if(key!=code&&event.getRepeatCount()==0){stop();key=code;started=SystemClock.uptimeMillis();adjust.accept(direction(code));handler.postDelayed(repeat,DELAY);}
        return true;
    }
    void stop(){key=0;handler.removeCallbacks(repeat);}
}
