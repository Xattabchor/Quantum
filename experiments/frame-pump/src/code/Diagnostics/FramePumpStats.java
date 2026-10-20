package code.Diagnostics;

/** Completed-paint wall clock, NOT GPU time. Intervals pair PREVIOUS paint cost
 * with the following gap: sumI == sumP + sumG exactly, before display rounding.
 * The first completed paint after warmup is only an anchor. No hot allocation.
 */
final class FramePumpStats {
    static final int F=0,B=1,S=2,K=3,E=4,C=5,H=6,Q=7,COUNT=8;
    final int[] mean=new int[COUNT],mean10=new int[COUNT];
    private final long[] previous=new long[COUNT],sums=new long[COUNT];
    private long warmUntil,lastStart=-1,lastEnd=-1,anchor;
    long sumI,sumP,sumG;
    int meanI10,meanP10,meanG10;
    int frames,fps10,meanI,meanP,meanG,minI,maxI,minP,maxP,queueSamples;
    private int lowI,highI,lowP,highP,qCount;
    void reset(long now){
        warmUntil=now+1000;lastStart=lastEnd=-1;frames=0;fps10=meanI=meanP=meanG=minI=maxI=minP=maxP=queueSamples=0;
        meanI10=meanP10=meanG10=0;for(int i=0;i<COUNT;i++)mean[i]=mean10[i]=0;clearWindow();
    }
    private void clearWindow(){frames=0;sumI=sumP=sumG=0;qCount=0;lowI=lowP=Integer.MAX_VALUE;highI=highP=0;for(int i=0;i<COUNT;i++)sums[i]=0;}
    boolean record(long start,long end,long[] ops){
        boolean valid=end>=start && (lastEnd<0 || start>=lastEnd);
        for(int i=0;i<COUNT;i++)if(ops[i]<(i==Q?-1:0))valid=false;
        if(!valid){reset(end);return false;}
        if(start<warmUntil)return false;
        boolean publish=false;
        if(lastStart>=0){
            long interval=start-lastStart,paint=lastEnd-lastStart,gap=start-lastEnd;
            sumI+=interval;sumP+=paint;sumG+=gap;frames++;
            lowI=Math.min(lowI,limited(interval));highI=Math.max(highI,limited(interval));lowP=Math.min(lowP,limited(paint));highP=Math.max(highP,limited(paint));
            for(int i=0;i<COUNT;i++)if(i!=Q || previous[i]>=0)sums[i]+=previous[i];
            if(previous[Q]>=0)qCount++;
            if(start-anchor>=3000 && frames>=2){
                fps10=(int)(frames*10000L/(start-anchor));meanI=limited(sumI/frames);meanP=limited(sumP/frames);meanG=limited(sumG/frames);
                meanI10=limited(sumI*10/frames);meanP10=limited(sumP*10/frames);meanG10=limited(sumG*10/frames);
                minI=lowI;maxI=highI;minP=lowP;maxP=highP;queueSamples=qCount;
                for(int i=0;i<COUNT;i++)mean[i]=i==Q?(qCount==0?-1:limited(sums[i]/qCount)):limited(sums[i]/frames);
                for(int i=0;i<COUNT;i++)mean10[i]=i==Q?(qCount==0?-1:limited(sums[i]*10/qCount)):limited(sums[i]*10/frames);
                clearWindow();anchor=start;publish=true;
            }
        }else anchor=start;
        lastStart=start;lastEnd=end;System.arraycopy(ops,0,previous,0,COUNT);return publish;
    }
    private static int limited(long n){return (int)Math.min(Integer.MAX_VALUE,n);}
}
