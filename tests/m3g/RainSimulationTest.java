package code.Rendering;

import code.utils.WeatherGenerator;
import java.lang.reflect.Field;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;

/** Active simulation work set, not a device FPS benchmark. */
public final class RainSimulationTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static WeatherGenerator rain(int count) {
        WeatherGenerator w=new WeatherGenerator(32,32,0,0x90a0b0,0x304050,0xffffff,0xcaccdf,false,null,4,22,16,16,1,1);
        w.coords=new float[count*2];w.info=new int[count*2];w.generate=1;w.setRenderer(DirectX7.M3G);
        for(int i=0;i<count;i++) {w.coords[i*2]=10;w.coords[i*2+1]=5;w.info[i*2]=1;w.info[i*2+1]=0;}
        return w;
    }
    private static Object field(WeatherGenerator w,String name) throws Exception {
        Field f=WeatherGenerator.class.getDeclaredField(name);f.setAccessible(true);return f.get(w);
    }
    private static void operation(WeatherGenerator w,int operation) {
        switch(operation) {
            case 0:w.update(10,0,false);break;
            case 1:w.moveX(1,50);break;
            case 2:w.moveX2(1,50);break;
            case 3:w.moveY(1,50);break;
            case 4:w.moveY2(1,50);break;
            case 5:w.move(1,10,0);break;
            case 6:w.moveB(1,10,0);break;
            case 7:w.createParticles();break;
        }
    }
    public static void main(String[] args) throws Exception {
        for(int total:new int[]{0,1,16,17,32,2048}) for(int op=0;op<8;op++) {
            WeatherGenerator w=rain(total);
            if(op==7) for(int i=0;i<total;i++) w.info[i*2]=0;
            float[] coords=w.coords.clone();int[] info=w.info.clone();
            operation(w,op);
            int[] active=(int[])field(w,"activeParticles");
            check(active.length==Math.min(16,total),"simulation didn't select <=16 rain slots");
            for(int i=16;i<total;i++) check(w.coords[i*2]==coords[i*2] && w.coords[i*2+1]==coords[i*2+1]
                    && w.info[i*2]==info[i*2] && w.info[i*2+1]==info[i*2+1],"operation "+op+" processed dormant rain "+i);
            Object random=field(w,"rainRandom");
            operation(w,op);
            check(field(w,"activeParticles")==active && field(w,"rainRandom")==random,"per-call worklist/Random allocation");
            // Same work set is drawn, rather than sampling frozen dormant rain.
            Graphics g=Image.createImage(32,64).getGraphics();w.paintAfterScene(g,0,DirectX7.M3G,true);
            check(g.lineCalls<=16,"draw limit exceeded");
        }
        System.out.println("PASS <=16 simulated rain slots / update+all six camera moves+creation / 2048-slot dormant tail / cached worklist+Random");

        WeatherGenerator w=rain(100);float old=w.coords[98];w.moveX2(1,50);
        check(w.coords[98]==old,"dormant slot moved in M3G");
        Graphics drawn=Image.createImage(32,32).getGraphics();w.paintAfterScene(drawn,0,DirectX7.M3G,false);
        check(drawn.lineCalls==16,"active rain wasn't drawn");
        for(int[] line:drawn.lineHistory) check(line[0]==14,"painter sampled dormant/unmoved rain");
        w.setRenderer(DirectX7.SOFTWARE);w.moveX2(1,50);
        check(w.coords[98]!=old && field(w,"activeParticles")==null,"Software didn't resume full pool");
        w.setRenderer(DirectX7.M3G);w.moveX2(1,50);Object active=field(w,"activeParticles");
        w.coords=w.coords.clone();w.info=w.info.clone();w.moveX2(1,50);
        check(field(w,"activeParticles")!=active,"public array replacement didn't rebuild worklist");
        w.generate=0;old=w.coords[98];w.moveX2(1,50);
        check(w.coords[98]==old,"stopped emission resumed dormant rain simulation");
        w.generate=2;old=w.coords[98];w.moveX2(1,50);
        check(w.coords[98]!=old && field(w,"activeParticles")==null,"snow generation was capped");
        w.generate=1;w.clearBuffers();w.createParticles();int live=0;
        for(int i=0;i<w.info.length;i+=2) if(w.info[i]==1) live++;
        check(live==16,"clear/create bypassed emission limit");
        System.out.println("PASS Software/snow mode full pool / renderer switching / replacement arrays / clear+create budget");

        // Preserve live snow anywhere in a mixed pool, then remove retired snow
        // from the hot list without turning it into unlimited rain emitters.
        w=rain(100);w.info[198]=2;w.moveX2(1,50);
        check(w.coords[198]==14,"snow outside rain budget froze");
        check(((int[])field(w,"activeParticles")).length==17,"mixed snow was omitted");
        w.info[198]=0;w.update(0,0,true);
        check(w.info[198]==0 && ((Integer)field(w,"activeCount")).intValue()==16,"retired snow spawned extra rain/stayed hot");
        // All-snow transition can recycle retired slots into the empty rain budget.
        w=rain(20);for(int i=0;i<20;i++) w.info[i*2]=2;
        w.moveX2(1,50);w.info[0]=0;w.update(0,0,true);
        check(((Integer)field(w,"rainSlots")).intValue()==1,"retired snow couldn't become rain emitter");
        w.lastUnderRoof=false;w.update(0,0,false);check(w.info[0]==1,"recycled rain emitter didn't spawn");
        w=rain(100);w.clearBuffers();w.update(10,0,true);
        for(int i=0;i<w.info.length;i+=2) check(w.info[i]==0,"rain spawned under roof");
        w.update(10,0,false);live=0;for(int i=0;i<w.info.length;i+=2) if(w.info[i]==1) live++;
        check(live==16,"outdoor respawn didn't respect simulation budget");
        w.move(1,10,0);w.update(0,0,false);
        check(!w.isZoom && !w.lastUnderRoof,"update lost camera/roof flags");
        w=rain(0);w.lighting=true;w.lightingTimes=new int[]{1000,2000};w.nextLighting=0;w.update(0,0,false);
        check(w.lastLighting>0 && w.nextLighting>w.lastLighting,"limited rain broke storm timer");
        System.out.println("PASS mixed snow remains active / retired-slot compaction+emission / roof+respawn / zoom flags / storm timing");
    }
}
