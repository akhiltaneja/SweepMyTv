package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, qualifiers="w960dp-h540dp-land-xhdpi")
public class PicksRowTest {
    @Test public void shortAndFullReelsHaveNoBlankTailBeforeOrDuringEitherBoundary() {
        for (int titles : new int[]{2,3,12}) for (boolean expanded : new boolean[]{false,true}) {
            var controller=Robolectric.buildActivity(Activity.class).setup();
            Activity activity=controller.get();
            PicksRow row=new PicksRow(activity,null);
            if (!expanded) row.fixedCards(140,210);
            int small=expanded ? PicksRow.SMALL_W : 140;
            List<View> views=new ArrayList<>();
            int count=PicksRow.viewCount(titles,960,small,PicksRow.GAP);
            for(int i=0;i<count;i++){View v=new View(activity);v.setTag(i%titles);views.add(v);}
            row.setItems(views,titles,0);activity.setContentView(row);
            float dp=activity.getResources().getDisplayMetrics().density;
            row.measure(View.MeasureSpec.makeMeasureSpec(Math.round(960*dp),View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(210*dp),View.MeasureSpec.EXACTLY));
            row.layout(0,0,Math.round(960*dp),Math.round(210*dp));
            for(int direction : new int[]{1,-1}) for(int move=0;move<titles*3+2;move++) {
                assertFilled(row,dp);
                int next=Math.floorMod(row.anchor()+direction,titles);
                float oldTarget=row.camera().target;
                row.setAnchor(next,true,direction);
                assertTrue("Camera must continue in the pressed direction",direction*(row.camera().target-oldTarget)>0);
                for(int frame=0;frame<32;frame++) {
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                    assertFilled(row,dp);
                }
                assertEquals(next,row.anchor());assertEquals(next,row.anchorItem().getTag());
                assertEquals("Selected title remains aligned after recycling",Math.round(40*dp),row.anchorItem().getLeft(),2);
                assertEquals("Views never accumulate",count,row.getChildCount());
                for(int i=0;i<count;i++)assertSame(views.get(i),row.getChildAt(i));
            }
            controller.pause().stop().destroy();
        }
    }

    @Test public void resizingArtworkAndFastDirectionChangesKeepTheReelFilled() {
        var controller=Robolectric.buildActivity(Activity.class).setup();Activity activity=controller.get();
        PicksRow row=new PicksRow(activity,null);List<View> views=new ArrayList<>();
        for(int i=0;i<12;i++)views.add(new View(activity));
        row.setItems(views,3,0);activity.setContentView(row);
        float dp=activity.getResources().getDisplayMetrics().density;
        row.measure(View.MeasureSpec.makeMeasureSpec(Math.round(960*dp),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.round(186*dp),View.MeasureSpec.EXACTLY));
        row.layout(0,0,Math.round(960*dp),Math.round(186*dp));
        for(int i=0;i<60;i++) {
            int direction=i%7<5?1:-1;
            row.setAnchor(Math.floorMod(row.anchor()+direction,3),true,direction);
            row.setExpandedWidth(i%2==0?128:420,true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48));assertFilled(row,dp);
        }
        controller.pause().stop().destroy();
    }

    private static void assertFilled(PicksRow row,float dp) {
        List<View> ordered=new ArrayList<>();
        for(int i=0;i<row.getChildCount();i++)ordered.add(row.getChildAt(i));
        ordered.sort(Comparator.comparingInt(View::getLeft));
        int edge=Math.round(40*dp),limit=Math.round(960*dp),gap=Math.round(PicksRow.GAP*dp);
        for(View child:ordered) {
            if(child.getRight()<edge)continue;
            assertTrue("Blank tail or recycling hole at "+edge+" before "+child.getLeft(),child.getLeft()<=edge+gap+2);
            edge=Math.max(edge,child.getRight());if(edge>=limit)return;
        }
        fail("Reel ended before viewport edge: "+edge+" < "+limit);
    }
}
