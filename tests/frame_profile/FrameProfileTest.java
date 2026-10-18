package code.Rendering;

import java.lang.reflect.Field;

/** A deterministic clock replaces ONLY the temporary helper's now() body. */
public final class FrameProfileTest {
    private static long time;
    public static long now() {return time;}
    private static void check(boolean condition,String reason) {
        if(!condition) throw new AssertionError(reason);
    }
    private static Object field(String name) throws Exception {
        Field f=FrameProfile.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);
    }
    private static void sample(long start) {
        time=start;FrameProfile.begin(7);
        time+=2;FrameProfile.push(FrameProfile.PREP);
        time+=8;FrameProfile.pop();
        FrameProfile.push(FrameProfile.SUBMIT);
        time+=4;FrameProfile.push(FrameProfile.API);
        time+=6;FrameProfile.pop();
        time+=2;FrameProfile.push(FrameProfile.READ);
        time+=3;FrameProfile.pop();
        time+=1;FrameProfile.pop();
        FrameProfile.push(FrameProfile.HUD);time+=2;FrameProfile.pop();
        FrameProfile.push(FrameProfile.LOGIC);time+=3;FrameProfile.push(FrameProfile.WAIT);
        time+=5;FrameProfile.pop();time+=1;FrameProfile.pop();
        FrameProfile.end();
    }
    public static void main(String[] args) throws Exception {
        FrameProfile.push(FrameProfile.API);FrameProfile.pop();FrameProfile.end(); // inactive no-ops
        sample(100);
        long[] values=(long[])field("frame");
        check(values[FrameProfile.OTHER]==2,"setup");
        check(values[FrameProfile.PREP]==8,"prep");
        check(values[FrameProfile.SUBMIT]==7,"child API/read time counted twice");
        check(values[FrameProfile.API]==6 && values[FrameProfile.READ]==3,"API/read");
        check(values[FrameProfile.LOGIC]==4 && values[FrameProfile.WAIT]==5,"sleep counted as AI");
        long sum=0;for(long value:values) sum+=value;
        check(sum==37,"exclusive buckets must cover entire frame");
        sample(200);
        time=1100;FrameProfile.begin(7);
        String[] lines=(String[])field("lines");
        check(lines[0].equals("PF L7 n2 I500.0 F37.0 G463.0"),"paired intervals and external gap: "+lines[0]);
        check(lines[1].equals("P8.0 V0.0 M0.0 S7.0 Z0.0"),"one-second averages");
        check(lines[2].equals("A6.0 R3.0 U0.0 K0.0"),"API and copy averages");
        check(lines[3].equals("X0.0 C0.0 H2.0 O2.0"),"HUD and setup averages");
        check(lines[4].equals("L4.0 E0.0 Q0.0 W5.0"),"logic and explicit sleep averages");
        check(lines[5].equals("TOP OUTSIDE 463.0ms"),"external pacing omitted from dominant bucket");
        System.out.println("PASS exclusive nested frame timing / I=F+G paired samples / no double-counted API or sleep / one-second averages");

        // Abandon the current frame. The next begin must not publish its data.
        time=1300;FrameProfile.begin(7);
        check(((Integer)field("frames")).intValue()==0,"abandoned frame polluted samples");
        time=1301;FrameProfile.push(FrameProfile.CPU);
        try {time=1302;throw new RuntimeException("test");}
        catch(RuntimeException expected) {}
        finally {FrameProfile.pop();}
        time=1303;FrameProfile.end();
        values=(long[])field("frame");check(values[FrameProfile.CPU]==1,"finally did not close CPU stage");
        time=1400;FrameProfile.begin(7);time=1399;FrameProfile.push(FrameProfile.API);
        time=1400;FrameProfile.pop();FrameProfile.end();
        check(((Long)field("previousStart")).longValue()==0,"backwards clock frame published");
        time=1500;FrameProfile.begin(7);
        check(((Integer)field("frames")).intValue()==1,"bad clock sample entered window");
        time=1501;FrameProfile.end();
        time=5000;FrameProfile.begin(7);
        check(((String[])field("lines"))[5].equals("TOP OUTSIDE 1798.0ms"),"long external stalls must not be silently discarded");
        FrameProfile.reset();FrameProfile.begin(7);
        check(((Integer)field("frames")).intValue()==0,"explicit pause reset polluted window");
        time=5001;FrameProfile.end();time=5010;FrameProfile.begin(8);
        check(((String[])field("lines"))[0].startsWith("PF L8:"),"old level statistics survived");
        FrameProfile.end();
        time=6000;FrameProfile.begin(9);time=6001;FrameProfile.push(FrameProfile.SUBMIT);
        time=6002;FrameProfile.push(FrameProfile.PACK);time=7202;FrameProfile.pop();
        time=7203;FrameProfile.pop();time=7204;FrameProfile.end();FrameProfile.begin(9);
        check(((String[])field("lines"))[5].equals("TOP PACK 1200.0ms"),"dominant nested bucket detection");
        FrameProfile.end();
        System.out.println("PASS exception-safe nesting / abandoned frames / backwards clock / explicit pause and level reset / long external stalls / inactive no-ops");
    }
}
